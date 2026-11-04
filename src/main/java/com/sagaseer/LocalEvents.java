package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The notable changes between two values of a section, for the Recent feed: levels, quests, diary tiers, log items and pets, kill
 * counts and bests, Combat Achievements, Slayer tasks, clues, raids, Hunter rumours and finished GE offers. Only what the values
 * show; pure functions.
 */
final class LocalEvents
{
    private LocalEvents() { }

    static List<LocalTrail.Event> between(String section, JsonArray before, JsonArray after, String at)
    {
        List<LocalTrail.Event> found = new ArrayList<>();
        try
        {
            switch (section)
            {
                case "state.skills":
                    for (Map.Entry<String, JsonObject[]> pair : pairs(before, after, "skillId").entrySet())
                    {
                        JsonObject was = pair.getValue()[0], now = pair.getValue()[1];
                        if (was != null && now.get("level").getAsInt() > was.get("level").getAsInt())
                            found.add(new LocalTrail.Event(skillName(pair.getKey()) + " level " + now.get("level").getAsInt(), at,
                                List.of("Level " + was.get("level").getAsInt() + " → " + now.get("level").getAsInt(),
                                    number(now.get("xp").getAsLong()) + " XP")));
                    }
                    break;
                case "state.quests":
                    for (JsonObject[] pair : pairs(before, after, "questId").values())
                        if (pair[0] != null && "FINISHED".equals(pair[1].get("state").getAsString())
                            && !"FINISHED".equals(pair[0].get("state").getAsString()))
                            found.add(new LocalTrail.Event("Quest complete: " + pair[1].get("name").getAsString(), at, List.of()));
                    break;
                case "state.diaries":
                    for (Map.Entry<String, JsonObject[]> pair : pairs(before, after, "region").entrySet())
                    {
                        JsonObject was = pair.getValue()[0], now = pair.getValue()[1];
                        if (was == null || !was.get("tierCounts").isJsonArray() || !now.get("tierCounts").isJsonArray()) continue;
                        JsonArray old = was.getAsJsonArray("tierCounts"), tiers = now.getAsJsonArray("tierCounts");
                        for (int tier = 0; tier < Math.min(old.size(), tiers.size()); tier++)
                        {
                            int done = tiers.get(tier).getAsJsonObject().get("completed").getAsInt();
                            int total = tiers.get(tier).getAsJsonObject().get("total").getAsInt();
                            int wasDone = old.get(tier).getAsJsonObject().get("completed").getAsInt();
                            if (done <= wasDone) continue;
                            String name = TIERS[tier] + " " + regionName(pair.getKey()) + " diary";
                            found.add(new LocalTrail.Event(done == total ? name + " complete" : name + " task done", at,
                                List.of(done + " of " + total + " tasks")));
                        }
                    }
                    break;
                case "state.collectionLog":
                    collectionLog(before, after, at, found);
                    break;
                case "state.bosses":
                    for (Map.Entry<String, JsonObject[]> pair : pairs(before, after, "activity").entrySet())
                    {
                        JsonObject was = pair.getValue()[0], now = pair.getValue()[1];
                        if (!now.get("count").isJsonNull() && !(was != null && !was.get("count").isJsonNull()
                            && was.get("count").getAsLong() >= now.get("count").getAsLong()))
                        {
                            Long from = was == null || was.get("count").isJsonNull() ? null : was.get("count").getAsLong();
                            long to = now.get("count").getAsLong();
                            found.add(new LocalTrail.Event(killCountTitle(pair.getKey(), from, to), at, List.of(),
                                "kc:" + pair.getKey(), from, to));
                        }
                        // A faster best than the one known (a first best isn't news).
                        if (was != null && present(now, "bestMillis") && present(was, "bestMillis")
                            && now.get("bestMillis").getAsLong() < was.get("bestMillis").getAsLong() && present(now, "bestText"))
                            found.add(new LocalTrail.Event(pair.getKey() + " personal best " + now.get("bestText").getAsString(), at,
                                List.of("Was " + (present(was, "bestText") ? was.get("bestText").getAsString() : "slower"))));
                    }
                    break;
                case "state.combatAchievements":
                {
                    JsonObject was = LocalTrail.first(before), now = LocalTrail.first(after);
                    int wasDone = done(was), nowDone = done(now);
                    if (was != null && nowDone > wasDone)
                    {
                        List<String> details = new ArrayList<>();
                        details.add(nowDone + " tasks done");
                        String gained = "";
                        if (present(now, "points"))
                        {
                            details.add(now.get("points").getAsInt() + " points");
                            if (present(was, "points") && now.get("points").getAsInt() > was.get("points").getAsInt())
                                { int more = now.get("points").getAsInt() - was.get("points").getAsInt(); gained = " (+" + more + (more == 1 ? " point)" : " points)"); }
                        }
                        found.add(new LocalTrail.Event((nowDone - wasDone == 1 ? "Combat Achievement done"
                            : (nowDone - wasDone) + " Combat Achievements done") + gained, at, details));
                    }
                    break;
                }
                case "state.slayer":
                {
                    // A streak rising means the held task was finished.
                    JsonObject was = LocalTrail.first(before), now = LocalTrail.first(after);
                    if (was == null || now == null || !was.get("assignment").isJsonObject()
                        || !(rose(was, now, "streak") || rose(was, now, "wildernessStreak") || rose(was, now, "mortimerStreak"))) break;
                    JsonObject task = was.getAsJsonObject("assignment");
                    String name = present(task, "name") ? task.get("name").getAsString() : "Slayer";
                    String points = present(was, "points") && present(now, "points") && now.get("points").getAsInt() > was.get("points").getAsInt()
                        ? " (+" + (now.get("points").getAsInt() - was.get("points").getAsInt()) + " points)" : "";
                    found.add(new LocalTrail.Event("Slayer task done: " + name + points, at,
                        present(now, "streak") ? List.of("Streak " + number(now.get("streak").getAsLong())) : List.of()));
                    break;
                }
                case "state.activities":
                    // Clues: a tier's count rising; its first line is news too.
                    for (Map.Entry<String, JsonObject[]> pair : pairs(before, after, "key").entrySet())
                    {
                        JsonObject was = pair.getValue()[0], now = pair.getValue()[1];
                        if (!pair.getKey().startsWith("CLUE:") || was != null && now.get("value").getAsLong() <= was.get("value").getAsLong()) continue;
                        found.add(new LocalTrail.Event(now.get("name").getAsString() + " clue #" + number(now.get("value").getAsLong()), at, List.of()));
                    }
                    break;
                case "state.raidRewards":
                {
                    // A new chest row is a new completion.
                    JsonObject was = LocalTrail.first(before), now = LocalTrail.first(after);
                    if (now == null || was != null && now.get("observedAt").getAsString().equals(was.get("observedAt").getAsString())) break;
                    List<String> items = new ArrayList<>();
                    for (JsonElement item : now.getAsJsonArray("items"))
                        items.add(item.getAsJsonObject().get("name").getAsString()
                            + (item.getAsJsonObject().get("quantity").getAsInt() > 1 ? " ×" + number(item.getAsJsonObject().get("quantity").getAsLong()) : ""));
                    String count = present(now, "completions") ? " #" + number(now.get("completions").getAsLong()) : "";
                    found.add(new LocalTrail.Event(now.get("name").getAsString() + count + (items.isEmpty() ? "" : " · " + items.get(0)), at, items));
                    break;
                }
                case "state.hunterRumours":
                {
                    JsonObject was = LocalTrail.first(before), now = LocalTrail.first(after);
                    if (was != null && now != null && present(was, "completed") && present(now, "completed")
                        && now.get("completed").getAsLong() > was.get("completed").getAsLong())
                        found.add(new LocalTrail.Event("Hunter rumour done #" + number(now.get("completed").getAsLong()), at, List.of()));
                    break;
                }
                case "state.grandExchange":
                    // An offer becoming bought or sold.
                    for (JsonObject[] pair : pairs(before, after, "slot").values())
                    {
                        String state = pair[1].get("state").getAsString();
                        if (!("BOUGHT".equals(state) || "SOLD".equals(state)) || pair[0] != null && state.equals(pair[0].get("state").getAsString())) continue;
                        JsonObject offer = pair[1];
                        String what = offer.get("name").getAsString() + (offer.get("done").getAsLong() > 1 ? " ×" + number(offer.get("done").getAsLong()) : "");
                        found.add(new LocalTrail.Event(("SOLD".equals(state) ? "Sold: " : "Bought: ") + what + " · "
                            + gp(offer.get("spent").getAsLong()), at, List.of()));
                    }
                    break;
                default:
                    break;
            }
        }
        catch (RuntimeException unexpected)
        {
            // A value of another shape shows no event rather than a wrong one.
            return List.of();
        }
        return found;
    }

    /**
     * New log items once each (a pet on its boss page and All Pets is one row, as "Pet: …"). A page seen for the first time is
     * not news.
     */
    private static void collectionLog(JsonArray before, JsonArray after, String at, List<LocalTrail.Event> found)
    {
        Map<Integer, LocalTrail.Event> items = new LinkedHashMap<>();
        Map<Integer, Boolean> pets = new HashMap<>();
        for (Map.Entry<String, JsonObject[]> pair : pairs(before, after, "page").entrySet())
        {
            if (pair.getValue()[0] == null) continue;
            Map<Integer, Integer> had = new HashMap<>();
            for (JsonElement item : pair.getValue()[0].getAsJsonArray("items"))
                had.put(item.getAsJsonObject().get("itemId").getAsInt(), item.getAsJsonObject().get("quantity").getAsInt());
            JsonObject page = pair.getValue()[1];
            JsonArray list = page.getAsJsonArray("items");
            int obtained = 0;
            for (JsonElement item : list) if (item.getAsJsonObject().get("quantity").getAsInt() > 0) obtained++;
            for (JsonElement entry : list)
            {
                JsonObject item = entry.getAsJsonObject();
                int id = item.get("itemId").getAsInt();
                if (item.get("quantity").getAsInt() < 1 || had.getOrDefault(id, 0) > 0) continue;
                if ("All Pets".equals(pair.getKey())) pets.put(id, true);
                if (items.containsKey(id)) continue;
                List<String> details = new ArrayList<>();
                details.add("Page: " + pair.getKey());
                details.add(obtained + " of " + list.size() + " on this page");
                if (page.has("killCounts") && page.get("killCounts").isJsonArray())
                    for (JsonElement count : page.getAsJsonArray("killCounts"))
                        details.add(count.getAsJsonObject().get("label").getAsString() + ": "
                            + number(count.getAsJsonObject().get("value").getAsLong()));
                items.put(id, new LocalTrail.Event("Collection log: " + item.get("name").getAsString(), at, details));
            }
        }
        for (Map.Entry<Integer, LocalTrail.Event> item : items.entrySet())
        {
            LocalTrail.Event event = item.getValue();
            found.add(pets.containsKey(item.getKey())
                ? new LocalTrail.Event(event.title.replace("Collection log: ", "Pet: "), at, event.details) : event);
        }
    }

    /** "Vorkath 45 → 51 (+6)", or "Vorkath kill count 51" when the count before isn't known. */
    static String killCountTitle(String boss, Long from, long to)
    {
        return from == null ? boss + " kill count " + number(to) : boss + " " + number(from) + " → " + number(to) + " (+" + number(to - from) + ")";
    }

    private static final String[] TIERS = {"Easy", "Medium", "Hard", "Elite"};

    private static boolean rose(JsonObject was, JsonObject now, String field)
    {
        return present(was, field) && present(now, field) && now.get(field).getAsLong() > was.get(field).getAsLong();
    }

    private static boolean present(JsonObject row, String field) { return row != null && row.has(field) && !row.get(field).isJsonNull(); }

    private static int done(JsonObject combat)
    {
        if (combat == null || !combat.get("tierCounts").isJsonArray()) return 0;
        int total = 0;
        for (JsonElement count : combat.getAsJsonArray("tierCounts")) total += count.getAsInt();
        return total;
    }

    /** Rows of both values by key: {before or null, after}. */
    private static Map<String, JsonObject[]> pairs(JsonArray before, JsonArray after, String key)
    {
        Map<String, JsonObject> old = new HashMap<>();
        for (JsonElement row : before) old.put(row.getAsJsonObject().get(key).getAsString(), row.getAsJsonObject());
        Map<String, JsonObject[]> pairs = new LinkedHashMap<>();
        for (JsonElement row : after)
        {
            String id = row.getAsJsonObject().get(key).getAsString();
            pairs.put(id, new JsonObject[] {old.get(id), row.getAsJsonObject()});
        }
        return pairs;
    }

    static String skillName(String id)
    {
        return id.isEmpty() ? id : id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
    }

    private static String regionName(String region)
    {
        switch (region)
        {
            case "western": return "Western Provinces";
            case "lumbridge": return "Lumbridge & Draynor";
            case "kourend": return "Kourend & Kebos";
            default: return skillName(region);
        }
    }

    static String number(long value) { return String.format(Locale.ROOT, "%,d", value); }

    /** gp the way the game shortens it: 14.5M, 210K, 950 gp. */
    static String gp(long value)
    {
        if (value >= 1_000_000_000L) return trim(value / 1_000_000_000.0) + "B";
        if (value >= 1_000_000L) return trim(value / 1_000_000.0) + "M";
        if (value >= 10_000L) return trim(value / 1_000.0) + "K";
        return number(value) + " gp";
    }

    private static String trim(double value) { return String.format(Locale.ROOT, "%.1f", value).replaceAll("\\.0$", ""); }
}
