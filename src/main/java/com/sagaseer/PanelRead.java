package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The side panel's read from SagaSeer for the player's own linked character: open in-game steps, the current task's Slayer card,
 * and the pinned Now step. Shown, never acted on; kept for the session.
 */
final class PanelRead
{
    static final int MAX_TODO = 10;
    static final int MAX_CARD = 8;
    static final int MAX_TEXT = 300;
    /** Bring holds up to 60 (40 items plus 20 containers); the others 40 or fewer. */
    static final int MAX_ITEMS = 60;
    static final int MAX_NOTE = 100;
    /** The card's item lists in panel order: server key and label. */
    static final String[][] ITEM_LISTS = {{"bring", "Bring"}, {"weapons", "Weapon"}, {"bank", "Bank"}, {"alch", "Alch"}, {"ignore", "Ignore"}};

    static final class Item
    {
        final int id;
        final String name;
        final String note;
        Item(int id, String name, String note)
        {
            this.id = id;
            this.name = name;
            this.note = note;
        }
    }

    final List<String> todo;
    final String slayerTask;
    final List<String> card;
    /** The lists that have items, by label. */
    final Map<String, List<Item>> items;
    final String now;
    final List<Ready> ready;
    final Killing killing;
    final Goal goal;
    final Instant at;

    static final class Ready
    {
        final String name, status, detail;
        final Instant at;
        Ready(String name, String status, Instant at, String detail)
        { this.name = name; this.status = status; this.at = at; this.detail = detail; }
    }

    static final class Missing
    {
        final String name, rate;
        final int chance;
        /** The server's chance per kill (rolls included), or null from an older server or an unknown rate. */
        final Double perKill;
        Missing(String name, String rate, int chance, Double perKill)
        { this.name = name; this.rate = rate; this.chance = chance; this.perKill = perKill; }
    }

    static final class Killing
    {
        final String boss;
        final int kc;
        final Integer obtained, total;
        final List<Missing> missing;
        final Instant killedAt;
        Killing(String boss, int kc, Integer obtained, Integer total, List<Missing> missing, Instant killedAt)
        { this.boss = boss; this.kc = kc; this.obtained = obtained; this.total = total; this.missing = missing; this.killedAt = killedAt; }
    }

    static final class Goal
    {
        final String name, next;
        final int percent;
        final Step now;
        Goal(String name, int percent, Step now, String next)
        { this.name = name; this.percent = percent; this.now = now; this.next = next; }
    }

    static final class Step
    {
        final String text;
        final Integer from, to;
        Step(String text, Integer from, Integer to)
        { this.text = text; this.from = from; this.to = to; }
    }

    PanelRead(List<String> todo, String slayerTask, List<String> card, Map<String, List<Item>> items, String now,
              List<Ready> ready, Killing killing, Goal goal, Instant at)
    {
        this.todo = todo;
        this.slayerTask = slayerTask;
        this.card = card;
        this.items = items;
        this.now = now;
        this.ready = ready;
        this.killing = killing;
        this.goal = goal;
        this.at = at;
    }

    /** The server's answer, bounded; null when not the expected shape. */
    static PanelRead parse(JsonObject body, Instant at)
    {
        try
        {
            List<String> todo = lines(body.getAsJsonArray("todo"), MAX_TODO);
            String task = null;
            List<String> card = List.of();
            Map<String, List<Item>> items = new LinkedHashMap<>();
            if (body.get("slayer").isJsonObject())
            {
                JsonObject slayer = body.getAsJsonObject("slayer");
                task = text(slayer.get("task"));
                card = lines(slayer.getAsJsonArray("card"), MAX_CARD);
                JsonObject lists = slayer.has("items") ? slayer.getAsJsonObject("items") : new JsonObject();
                for (String[] list : ITEM_LISTS)
                {
                    List<Item> rows = lists.has(list[0]) ? items(lists.getAsJsonArray(list[0])) : List.of();
                    if (!rows.isEmpty()) items.put(list[1], rows);
                }
            }
            String now = body.get("now").isJsonNull() ? null : text(body.get("now"));
            // Optional cards are independent: a bad new field never hides an older server's valid panel.
            List<Ready> ready;
            Killing killing;
            Goal goal;
            try { ready = ready(body.get("ready")); } catch (RuntimeException bad) { ready = List.of(); }
            try { killing = killing(body.get("killing")); } catch (RuntimeException bad) { killing = null; }
            try { goal = goal(body.get("goal")); } catch (RuntimeException bad) { goal = null; }
            return new PanelRead(todo, task, card, items, now, ready, killing, goal, at);
        }
        catch (RuntimeException unreadable)
        {
            return null;
        }
    }

    /** True when older than the panel shows unmarked. */
    boolean stale(Instant now) { return Duration.between(at, now).compareTo(Duration.ofMinutes(10)) > 0; }

    private static List<Ready> ready(JsonElement value)
    {
        if (value == null || value.isJsonNull()) return List.of();
        JsonArray rows = value.getAsJsonArray();
        if (rows.size() > 5) throw new IllegalArgumentException("too many ready rows");
        List<Ready> out = new ArrayList<>();
        for (JsonElement element : rows)
        {
            JsonObject item = element.getAsJsonObject();
            String name = required(item, "name"), status = required(item, "status"), detail = required(item, "detail");
            if (!"READY".equals(status) && !"SOON".equals(status)) throw new IllegalArgumentException("status");
            Instant at = instant(item.get("at"));
            if ("SOON".equals(status) && at == null) throw new IllegalArgumentException("at");
            out.add(new Ready(name, status, at, detail));
        }
        return out;
    }

    private static Killing killing(JsonElement value)
    {
        if (value == null || value.isJsonNull()) return null;
        JsonObject item = value.getAsJsonObject();
        String boss = required(item, "boss");
        int kc = number(item.get("kc"), 10_000_000);
        Instant killedAt = instant(item.get("killedAt"));
        if (killedAt == null) throw new IllegalArgumentException("killedAt");
        Integer obtained = item.has("obtained") ? number(item.get("obtained"), 200) : null;
        Integer total = item.has("total") ? number(item.get("total"), 200) : null;
        if (obtained != null && (total == null || obtained > total)) throw new IllegalArgumentException("log counts");
        List<Missing> missing = new ArrayList<>();
        if (item.has("missing"))
        {
            JsonArray rows = item.getAsJsonArray("missing");
            if (rows.size() > 3) throw new IllegalArgumentException("missing");
            for (JsonElement element : rows)
            {
                JsonObject drop = element.getAsJsonObject();
                missing.add(new Missing(required(drop, "name"), required(drop, "rate"), number(drop.get("chance"), 100),
                    probability(drop.get("perKill"))));
            }
        }
        return new Killing(boss, kc, obtained, total, missing, killedAt);
    }

    private static Goal goal(JsonElement value)
    {
        if (value == null || value.isJsonNull()) return null;
        JsonObject item = value.getAsJsonObject();
        String name = required(item, "name");
        int percent = number(item.get("percent"), 100);
        Step now = null;
        if (item.has("now") && !item.get("now").isJsonNull())
        {
            JsonObject step = item.getAsJsonObject("now");
            now = new Step(required(step, "text"), optionalNumber(step.get("from")), optionalNumber(step.get("to")));
        }
        String next = item.has("next") && !item.get("next").isJsonNull() ? required(item, "next") : null;
        return new Goal(name, percent, now, next);
    }

    private static String required(JsonObject item, String key)
    {
        JsonElement raw = item.get(key);
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key);
        String value = text(raw);
        if (value == null) throw new IllegalArgumentException(key);
        return value;
    }

    private static Instant instant(JsonElement value)
    {
        if (value == null || value.isJsonNull()) return null;
        Instant at = Instant.parse(value.getAsString());
        if (at.isBefore(Instant.parse("2020-01-01T00:00:00Z")) || at.isAfter(Instant.parse("2100-01-01T00:00:00Z")))
            throw new IllegalArgumentException("instant");
        return at;
    }

    private static int number(JsonElement value, int max)
    {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("number");
        int parsed = value.getAsInt();
        if (parsed < 0 || parsed > max || !value.getAsString().equals(Integer.toString(parsed))) throw new IllegalArgumentException("number");
        return parsed;
    }

    /** A chance strictly between 0 and 1, or null when absent. */
    private static Double probability(JsonElement value)
    {
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("perKill");
        double p = value.getAsDouble();
        if (!(p > 0 && p < 1)) throw new IllegalArgumentException("perKill");
        return p;
    }

    private static Integer optionalNumber(JsonElement value) { return value == null || value.isJsonNull() ? null : number(value, 10_000_000); }

    private static List<String> lines(JsonArray rows, int max)
    {
        List<String> out = new ArrayList<>();
        for (JsonElement row : rows)
        {
            if (out.size() >= max) break;
            String line = text(row);
            if (line != null) out.add(line);
        }
        return out;
    }

    /** Up to MAX_ITEMS; items without an id or name are dropped, long notes cut. */
    private static List<Item> items(JsonArray rows)
    {
        List<Item> out = new ArrayList<>();
        for (JsonElement row : rows)
        {
            if (out.size() >= MAX_ITEMS) break;
            JsonObject item = row.getAsJsonObject();
            int id = item.get("id").getAsInt();
            String name = text(item.get("name"));
            String note = text(item.get("note"));
            if (id < 0 || name == null) continue;
            out.add(new Item(id, name, note != null && note.length() > MAX_NOTE ? note.substring(0, MAX_NOTE) : note));
        }
        return out;
    }

    private static String text(JsonElement value)
    {
        if (value == null || value.isJsonNull()) return null;
        String text = value.getAsString().trim();
        if (text.isEmpty()) return null;
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }
}
