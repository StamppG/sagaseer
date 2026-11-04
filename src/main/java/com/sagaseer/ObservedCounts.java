package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.Locale;

/**
 * Counts of what the plugin saw while running, for things the game keeps no counter for: superiors (the line RuneLite's Slayer
 * plugin reads, per task) and drop attempts (Loot Tracker events). Counted per session and sent whole; the server adds sessions
 * up. Nothing is kept on disk. Each type has its own "since": turning a type off drops its count and since, so a fresh count
 * never reads as covering the earlier time.
 */
final class ObservedCounts
{
    static final String SUPERIOR_MESSAGE = "A superior foe has appeared...";
    static final int MAX_ACCOUNTS = 10;
    static final int MAX_TASKS = 100;
    static final int MAX_SOURCES = 400;
    static final int MAX_NAME = 100;
    static final int MAX_CONSUMABLES = 200;
    /** More than this at once isn't one kill's loot: ignored, not guessed. */
    static final int MAX_AMOUNT = 1000;

    /**
     * The Loot Tracker records one Seed pack per Take click and a pack can take several clicks, so a pack counts only when it
     * leaves the inventory.
     */
    static final String SEED_PACK = "Seed pack";

    /** Packs that left the inventory since the click; 0 when the click went unseen. */
    static int seedPacksOpened(int heldAtClick, int heldNow)
    {
        return heldAtClick < 0 || heldNow < 0 ? 0 : Math.max(0, heldAtClick - heldNow);
    }

    /** A Take click on a seed pack, with the packs held then (-1: unreadable). */
    synchronized void seedPackClick(int held) { seedPacksAtClick = held; }

    /**
     * How much one Loot Tracker event counts: a Seed pack counts the packs opened since its click and uses the click up. While
     * not counting, a Seed pack event only drops its click and the inventory is never read.
     */
    synchronized int lootAmount(String kind, String name, int amount, boolean counting, IntSupplier seedPacksHeld)
    {
        if (!"EVENT".equals(kind) || !SEED_PACK.equals(name)) return counting ? amount : 0;
        if (!counting)
        {
            seedPacksAtClick = -1;
            return 0;
        }
        int opened = seedPacksOpened(seedPacksAtClick, seedPacksHeld.getAsInt());
        seedPacksAtClick = -1;
        return opened;
    }

    /** A logout or hop drops a waiting click. */
    synchronized void forgetSeedPackClick() { seedPacksAtClick = -1; }

    /** PLAYER (another player's name) and UNKNOWN are never counted. */
    static boolean counted(String kind) { return "NPC".equals(kind) || "EVENT".equals(kind) || "PICKPOCKET".equals(kind); }

    private static final class Session
    {
        String superiorsSince;
        String attemptsSince;
        String consumablesSince;
        String xpSince;
        Instant consumablesLast;
        Instant xpLast;
        Instant last;
        final Map<String, Integer> tasks = new LinkedHashMap<>();
        int unplaced;
        String task;
        final Map<String, long[]> attempts = new LinkedHashMap<>();
        boolean truncated;
        final Map<String, Integer> eaten = new LinkedHashMap<>();
        final Map<String, Integer> drunk = new LinkedHashMap<>();
        boolean consumablesTruncated;
        final Map<String, Integer> xpBaseline = new LinkedHashMap<>();
        final Map<String, Integer> xpTick = new LinkedHashMap<>();
        final Map<String, Integer> xpTickSum = new LinkedHashMap<>();
        final Map<String, int[]> xpBest = new LinkedHashMap<>();
        final Map<String, Instant> xpBestAt = new LinkedHashMap<>();
        Session(Instant at) { last = at; }
    }

    private final Map<String, Session> sessions = new LinkedHashMap<>(16, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Session> eldest) { return size() > MAX_ACCOUNTS; }
    };

    /**
     * Packs held at the last Take click (-1: none). Every reset clears it, so a click before a pause or logout never matches a
     * later event.
     */
    private int seedPacksAtClick = -1;

    /** The plugin was turned off or on: nothing carries over. */
    synchronized void reset() { sessions.clear(); seedPacksAtClick = -1; }

    /** A data type turned off drops its counts; the others keep theirs. */
    synchronized void resetSuperiors()
    {
        for (Session session : sessions.values())
        {
            session.tasks.clear();
            session.unplaced = 0;
            session.task = null;
            session.superiorsSince = null;
        }
    }

    synchronized void resetAttempts()
    {
        seedPacksAtClick = -1;
        for (Session session : sessions.values())
        {
            session.attempts.clear();
            session.truncated = false;
            session.attemptsSince = null;
        }
    }

    synchronized void resetConsumables()
    {
        for (Session session : sessions.values())
        {
            session.eaten.clear();
            session.drunk.clear();
            session.consumablesTruncated = false;
            session.consumablesSince = null;
            session.consumablesLast = null;
        }
    }

    synchronized void resetXpHits()
    {
        for (Session session : sessions.values())
        {
            session.xpBaseline.clear();
            session.xpTick.clear();
            session.xpTickSum.clear();
            session.xpBest.clear();
            session.xpBestAt.clear();
            session.xpSince = null;
            session.xpLast = null;
        }
    }

    /** A hop needs a fresh first reading per skill; observed hits stay. */
    synchronized void resetXpBaseline()
    {
        for (Session session : sessions.values())
        {
            session.xpBaseline.clear();
            session.xpTick.clear();
            session.xpTickSum.clear();
        }
    }

    static boolean isSuperiorMessage(String message) { return message != null && message.contains(SUPERIOR_MESSAGE); }

    private Session session(String account, Instant at)
    {
        return sessions.computeIfAbsent(account, key -> new Session(at));
    }

    /** The Slayer read now (or null): the task the next superior counts for. */
    synchronized void task(String account, JsonObject slayerRow, Instant at)
    {
        if (account == null || slayerRow == null) return;
        Session session = session(account, at);
        if (session.superiorsSince == null) session.superiorsSince = ChangeAwareSourceCapture.time(at);
        String task = slayerRow.has("assignment") && slayerRow.get("assignment").isJsonObject()
            ? text(slayerRow.getAsJsonObject("assignment"), "name") : null;
        if (!Objects.equals(task, session.task)) session.last = at;
        session.task = task;
    }

    /** A superior counts for the task last read, else as unplaced. */
    synchronized void superior(String account, Instant at)
    {
        if (account == null) return;
        Session session = session(account, at);
        if (session.superiorsSince == null) session.superiorsSince = ChangeAwareSourceCapture.time(at);
        session.last = at;
        String task = session.task;
        if (task == null || (session.tasks.size() >= MAX_TASKS && !session.tasks.containsKey(task))) session.unplaced++;
        else session.tasks.merge(task, 1, Integer::sum);
    }

    /** One recognised loot event: kind, name and how many kills it covers. */
    synchronized void loot(String account, String kind, String name, int amount, Instant at)
    {
        String trimmed = name == null ? "" : name.trim();
        if (account == null || !counted(kind) || trimmed.isEmpty() || trimmed.length() > MAX_NAME || amount < 1 || amount > MAX_AMOUNT) return;
        Session session = session(account, at);
        if (session.attemptsSince == null) session.attemptsSince = ChangeAwareSourceCapture.time(at);
        String key = kind + "|" + trimmed;
        long[] count = session.attempts.get(key);
        if (count == null)
        {
            if (session.attempts.size() >= MAX_SOURCES) { session.truncated = true; return; }
            count = new long[1];
            session.attempts.put(key, count);
        }
        count[0] += amount;
        session.last = at;
    }

    /** An eat or drink line, counted by kind and name. */
    synchronized void consumed(String account, String kind, String name, Instant at)
    {
        if (account == null || at == null || !("EAT".equals(kind) || "DRINK".equals(kind))) return;
        String clean = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (clean.isEmpty() || clean.length() > MAX_NAME) return;
        Session session = session(account, at);
        Map<String, Integer> list = "EAT".equals(kind) ? session.eaten : session.drunk;
        if (!list.containsKey(clean) && session.eaten.size() + session.drunk.size() >= MAX_CONSUMABLES)
        {
            session.consumablesTruncated = true;
            return;
        }
        if (session.consumablesSince == null) session.consumablesSince = ChangeAwareSourceCapture.time(at);
        list.merge(clean, 1, Integer::sum);
        session.consumablesLast = at;
    }

    /** The first StatChanged after login or a hop is a baseline; rises in one tick form one hit. */
    synchronized void xp(String account, String skill, int total, int tick, Instant at)
    {
        if (account == null || skill == null || skill.isBlank() || at == null || total < 0) return;
        Session session = session(account, at);
        Integer before = session.xpBaseline.put(skill, total);
        if (before == null) return;
        int rise = total - before;
        if (rise <= 0) return;
        int sum = session.xpTick.getOrDefault(skill, -1) == tick ? session.xpTickSum.getOrDefault(skill, 0) + rise : rise;
        session.xpTick.put(skill, tick);
        session.xpTickSum.put(skill, sum);
        if (session.xpSince == null) session.xpSince = ChangeAwareSourceCapture.time(at);
        if (!session.xpBest.containsKey(skill) || sum > session.xpBest.get(skill)[0])
        {
            session.xpBest.put(skill, new int[] {sum});
            session.xpBestAt.put(skill, at);
        }
        session.xpLast = at;
    }

    synchronized Observation consumables(String account)
    {
        Session session = account == null ? null : sessions.get(account);
        if (session == null || session.consumablesSince == null || session.eaten.isEmpty() && session.drunk.isEmpty()) return Observation.notRead();
        JsonObject row = new JsonObject();
        row.addProperty("since", session.consumablesSince);
        row.addProperty("truncated", session.consumablesTruncated);
        row.add("eaten", consumableRows(session.eaten));
        row.add("drunk", consumableRows(session.drunk));
        JsonArray value = new JsonArray();
        value.add(row);
        return new Observation(value, ChangeAwareSourceCapture.time(session.consumablesLast));
    }

    private static JsonArray consumableRows(Map<String, Integer> counts)
    {
        JsonArray rows = new JsonArray();
        for (Map.Entry<String, Integer> entry : counts.entrySet())
        {
            JsonObject row = new JsonObject();
            row.addProperty("name", entry.getKey());
            row.addProperty("count", entry.getValue());
            rows.add(row);
        }
        return rows;
    }

    synchronized Observation xpHits(String account)
    {
        Session session = account == null ? null : sessions.get(account);
        if (session == null || session.xpSince == null || session.xpBest.isEmpty()) return Observation.notRead();
        JsonArray skills = new JsonArray();
        for (Map.Entry<String, int[]> entry : session.xpBest.entrySet())
        {
            JsonObject hit = new JsonObject();
            hit.addProperty("skill", entry.getKey());
            hit.addProperty("xp", entry.getValue()[0]);
            hit.addProperty("at", ChangeAwareSourceCapture.time(session.xpBestAt.get(entry.getKey())));
            skills.add(hit);
        }
        JsonObject row = new JsonObject();
        row.addProperty("since", session.xpSince);
        row.add("skills", skills);
        JsonArray value = new JsonArray();
        value.add(row);
        return new Observation(value, ChangeAwareSourceCapture.time(session.xpLast));
    }

    /** This account's superiors, or nothing to send while it has none. */
    synchronized Observation superiors(String account)
    {
        Session session = account == null ? null : sessions.get(account);
        if (session == null || session.superiorsSince == null) return Observation.notRead();
        if (session.tasks.isEmpty() && session.unplaced == 0) return Observation.notRead();
        JsonArray list = new JsonArray();
        for (Map.Entry<String, Integer> entry : session.tasks.entrySet())
        {
            JsonObject row = new JsonObject();
            row.addProperty("task", entry.getKey());
            row.addProperty("seen", entry.getValue());
            list.add(row);
        }
        JsonObject row = new JsonObject();
        row.addProperty("since", session.superiorsSince);
        row.add("tasks", list);
        row.addProperty("unplaced", session.unplaced);
        JsonArray value = new JsonArray();
        value.add(row);
        return new Observation(value, ChangeAwareSourceCapture.time(session.last));
    }

    synchronized Observation attempts(String account)
    {
        Session session = account == null ? null : sessions.get(account);
        if (session == null || session.attempts.isEmpty() || session.attemptsSince == null) return Observation.notRead();
        JsonArray list = new JsonArray();
        for (Map.Entry<String, long[]> entry : session.attempts.entrySet())
        {
            int bar = entry.getKey().indexOf('|');
            JsonObject row = new JsonObject();
            row.addProperty("kind", entry.getKey().substring(0, bar));
            row.addProperty("name", entry.getKey().substring(bar + 1));
            row.addProperty("count", entry.getValue()[0]);
            list.add(row);
        }
        JsonObject row = new JsonObject();
        row.addProperty("since", session.attemptsSince);
        row.addProperty("truncated", session.truncated);
        row.add("rows", list);
        JsonArray value = new JsonArray();
        value.add(row);
        return new Observation(value, ChangeAwareSourceCapture.time(session.last));
    }

    private static String text(JsonObject row, String field)
    {
        JsonElement value = row.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        String name = value.getAsString().trim();
        return name.isEmpty() || name.length() > MAX_NAME ? null : name;
    }
}
