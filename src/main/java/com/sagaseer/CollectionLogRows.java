package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.gameval.ItemID;

/**
 * The state.collectionLog rows, one per page. Changes seen in game (a full sync, a page open, a new-item message) are applied in
 * order onto the latest known rows; nothing is sent if they change nothing.
 */
final class CollectionLogRows
{
    private static final int MAX_ROWS = 300;
    private static final int MAX_ITEMS = 200;
    private static final int MAX_KILL_COUNTS = 10;
    private static final int MAX_LABEL = 100;
    private static final int MAX_KILL_COUNT = 10_000_000;
    private static final Pattern KILL_COUNT = Pattern.compile("^(.{1,100}?):\\s*([0-9][0-9,]*)$");
    /**
     * A page header's personal best ("Personal Best Corrupted: 12:23"): h:mm:ss or m:ss, hundredths optional; "N/A" is not a
     * time.
     */
    private static final Pattern PERSONAL_BEST = Pattern.compile(
        "^Personal Best(?: (.{1,40}?))?:\\s*((?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,2}))?)$");
    private static final int MAX_BESTS = 10;
    private static final long MAX_BEST_MILLIS = 86_400_000L;

    abstract static class Change
    {
        final Instant at;

        Change(Instant at) { this.at = at; }

        abstract void applyTo(Map<String, JsonObject> rows);
    }

    /** One item of a log page as the game shows it. */
    static final class ItemQuantity
    {
        final int itemId;
        final String name;
        final int quantity;

        ItemQuantity(int itemId, String name, int quantity)
        {
            this.itemId = itemId;
            this.name = name;
            this.quantity = quantity;
        }
    }

    static final class FullSync extends Change
    {
        private final Map<Integer, Integer> quantities;
        private final CollectionLogLayout layout;

        /** layout may be null: then only known pages update. */
        FullSync(Instant at, Map<Integer, Integer> quantities, CollectionLogLayout layout)
        {
            super(at);
            this.quantities = Map.copyOf(quantities);
            this.layout = layout;
        }

        @Override
        void applyTo(Map<String, JsonObject> rows)
        {
            if (layout == null)
            {
                Set<Integer> pageIds = new HashSet<>();
                for (JsonObject row : rows.values())
                    for (JsonElement item : row.getAsJsonArray("items"))
                        pageIds.add(item.getAsJsonObject().get("itemId").getAsInt());
                Map<Integer, Integer> slots = onPages(quantities, pageIds);
                for (JsonObject row : rows.values())
                    for (JsonElement item : row.getAsJsonArray("items"))
                    {
                        JsonObject object = item.getAsJsonObject();
                        object.addProperty("quantity",
                            slots.getOrDefault(object.get("itemId").getAsInt(), 0));
                        // A rebuilt page drops an earlier upload's mark.
                        object.remove("confirmed");
                    }
                return;
            }
            Map<Integer, Integer> slots = onPages(quantities, pageIds(layout));
            Map<String, JsonObject> before = new TreeMap<>(rows);
            rows.clear();
            for (CollectionLogLayout.Page page : layout.pages)
            {
                JsonArray items = new JsonArray();
                for (int i = 0; i < page.itemIds.length; i++)
                    items.add(item(page.itemIds[i], page.itemNames[i],
                        slots.getOrDefault(page.itemIds[i], 0)));
                JsonObject old = before.get(page.name);
                rows.put(page.name, row(page.name, page.tab, page.order, items,
                    old == null ? JsonNull.INSTANCE : old.get("killCounts"),
                    old == null || !old.has("bests") ? JsonNull.INSTANCE : old.get("bests")));
            }
        }
    }

    /**
     * Log slots the game streams under a gameval *_DUMMY id while the pages list the item's own id. Only these move: other
     * variants (charged, trimmed, ornament) are separate items the game doesn't count for the slot.
     */
    static final Map<Integer, Integer> LOG_DUMMIES = Map.ofEntries(
        Map.entry(ItemID.TOL_TEA_DUMMY, ItemID.TOL_TEA),
        Map.entry(ItemID.TOL_PLAIN_SACK_DUMMY, ItemID.TOL_PLAIN_SACK),
        Map.entry(ItemID.TOL_GREEN_SACK_DUMMY, ItemID.TOL_GREEN_SACK),
        Map.entry(ItemID.TOL_RED_SACK_DUMMY, ItemID.TOL_RED_SACK),
        Map.entry(ItemID.TOL_BLACK_SACK_DUMMY, ItemID.TOL_BLACK_SACK),
        Map.entry(ItemID.TOL_GOLD_SACK_DUMMY, ItemID.TOL_GOLD_SACK),
        Map.entry(ItemID.TOL_RUNE_SACK_DUMMY, ItemID.TOL_RUNE_SACK),
        Map.entry(ItemID.ABYSSALSIRE_UNSIRED_DUMMY, ItemID.ABYSSALSIRE_UNSIRED),
        Map.entry(ItemID.COAL_BAG_DUMMY, ItemID.COAL_BAG),
        Map.entry(ItemID.GEM_BAG_DUMMY, ItemID.GEM_BAG),
        Map.entry(ItemID.PLANK_SACK_DUMMY, ItemID.PLANK_SACK),
        Map.entry(ItemID.FLAMTAER_BAG_DUMMY, ItemID.FLAMTAER_BAG),
        Map.entry(ItemID.AMULET_OF_CHEMISTRY_IMBUED_CHARGED_DUMMY, ItemID.AMULET_OF_CHEMISTRY_IMBUED_CHARGED),
        Map.entry(ItemID.YAMA_DOSSIER_DUMMY, ItemID.YAMA_DOSSIER));

    /** Streamed quantities by page item id; a listed dummy counts for its page item unless that id was streamed too. */
    static Map<Integer, Integer> onPages(Map<Integer, Integer> streamed, Set<Integer> pageIds)
    {
        Map<Integer, Integer> slots = new HashMap<>();
        for (Map.Entry<Integer, Integer> item : streamed.entrySet())
            if (pageIds.contains(item.getKey())) slots.put(item.getKey(), item.getValue());
        for (Map.Entry<Integer, Integer> item : streamed.entrySet())
        {
            Integer pageId = dummyFor(item.getKey(), streamed, pageIds);
            if (pageId != null) slots.merge(pageId, item.getValue(), Math::max);
        }
        return slots;
    }

    /** The page item a streamed dummy counts for, or null. */
    static Integer dummyFor(int streamedId, Map<Integer, Integer> streamed, Set<Integer> pageIds)
    {
        Integer pageId = LOG_DUMMIES.get(streamedId);
        return pageId != null && !pageIds.contains(streamedId) && pageIds.contains(pageId)
            && !streamed.containsKey(pageId) ? pageId : null;
    }

    static Set<Integer> pageIds(CollectionLogLayout layout)
    {
        Set<Integer> pageIds = new HashSet<>();
        for (CollectionLogLayout.Page page : layout.pages)
            for (int itemId : page.itemIds) pageIds.add(itemId);
        return pageIds;
    }

    /**
     * A page open replaces the page's kill-count and best lines and updates counts of items it already owns. It never changes
     * what is owned: other plugins can change the page's look (Collection Log Hider). Ownership comes from the full sync and the
     * new-item message; a new page waits for the next full sync.
     */
    static final class PageOpen extends Change
    {
        private final String page;
        private final List<ItemQuantity> items;
        private final JsonElement killCounts;
        private final JsonElement bests;

        /** A page with no personal best lines. */
        PageOpen(Instant at, String page, List<ItemQuantity> items, JsonArray killCounts)
        {
            this(at, page, items, killCounts, new JsonArray());
        }

        PageOpen(Instant at, String page, List<ItemQuantity> items, JsonArray killCounts, JsonArray bests)
        {
            super(at);
            this.page = page;
            this.items = List.copyOf(items);
            this.killCounts = killCounts.size() == 0 ? JsonNull.INSTANCE : killCounts;
            this.bests = bests.size() == 0 ? JsonNull.INSTANCE : bests;
        }

        @Override
        void applyTo(Map<String, JsonObject> rows)
        {
            if (page.isEmpty() || page.length() > MAX_LABEL || items.size() > MAX_ITEMS) return;
            JsonObject old = rows.get(page);
            if (old == null) return;
            Map<Integer, Integer> read = new HashMap<>();
            for (ItemQuantity item : items) if (item.quantity > 0) read.put(item.itemId, item.quantity);
            JsonArray array = old.getAsJsonArray("items").deepCopy();
            for (JsonElement element : array)
            {
                JsonObject item = element.getAsJsonObject();
                Integer count = read.get(item.get("itemId").getAsInt());
                if (count != null && item.get("quantity").getAsInt() > 0) item.addProperty("quantity", count);
            }
            rows.put(page, row(page, old.get("tab").getAsString(), old.get("pageOrder").getAsInt(), array,
                killCounts.deepCopy(), bests.deepCopy()));
        }
    }

    /** "New item added to your collection log": at least one, wherever it is. */
    static final class NewItem extends Change
    {
        private final String name;

        NewItem(Instant at, String name)
        {
            super(at);
            this.name = name;
        }

        @Override
        void applyTo(Map<String, JsonObject> rows)
        {
            Integer id = idOf(rows);
            if (id == null) return;
            for (JsonObject row : rows.values())
                for (JsonElement item : row.getAsJsonArray("items"))
                {
                    JsonObject object = item.getAsJsonObject();
                    if (object.get("itemId").getAsInt() == id && object.get("quantity").getAsInt() < 1)
                        object.addProperty("quantity", 1);
                }
        }

        /** The item's one id on these pages; null when unknown or ambiguous. */
        Integer idOf(Map<String, JsonObject> rows)
        {
            String wanted = normalize(name);
            Set<Integer> ids = new HashSet<>();
            for (JsonObject row : rows.values())
                for (JsonElement item : row.getAsJsonArray("items"))
                    if (normalize(item.getAsJsonObject().get("name").getAsString()).equals(wanted))
                        ids.add(item.getAsJsonObject().get("itemId").getAsInt());
            return ids.size() == 1 ? ids.iterator().next() : null;
        }

        private static String normalize(String text)
        {
            return text.trim().toLowerCase(Locale.ROOT);
        }
    }

    private CollectionLogRows() { }

    /** A header's personal best lines: version (null for the page's own boss), time as shown and in milliseconds. */
    static JsonArray bests(List<String> headerLines)
    {
        JsonArray bests = new JsonArray();
        for (String line : headerLines)
        {
            Matcher match = PERSONAL_BEST.matcher(line.trim());
            if (!match.matches() || bests.size() == MAX_BESTS) continue;
            long hours = match.group(3) == null ? 0 : Long.parseLong(match.group(3));
            long minutes = Long.parseLong(match.group(4)), seconds = Long.parseLong(match.group(5));
            String hundredths = match.group(6) == null ? "0" : (match.group(6) + "0").substring(0, 2);
            if (seconds > 59 || (match.group(3) != null && minutes > 59)) continue;
            long millis = ((hours * 60 + minutes) * 60 + seconds) * 1000 + Long.parseLong(hundredths) * 10;
            if (millis <= 0 || millis > MAX_BEST_MILLIS) continue;
            JsonObject best = new JsonObject();
            if (match.group(1) == null) best.add("version", JsonNull.INSTANCE); else best.addProperty("version", match.group(1).trim());
            best.addProperty("text", match.group(2));
            best.addProperty("millis", millis);
            bests.add(best);
        }
        return bests;
    }

    /** A header's "Label: N" lines as shown; "Obtained: 3/8" and personal best lines are not counts. */
    static JsonArray killCounts(List<String> headerLines)
    {
        JsonArray counts = new JsonArray();
        for (String line : headerLines)
        {
            Matcher match = KILL_COUNT.matcher(line.trim());
            if (!match.matches() || counts.size() == MAX_KILL_COUNTS
                || line.trim().toLowerCase(Locale.ROOT).startsWith("personal best")) continue;
            try
            {
                long value = Long.parseLong(match.group(2).replace(",", ""));
                if (value > MAX_KILL_COUNT) continue;
                JsonObject count = new JsonObject();
                count.addProperty("label", match.group(1).trim());
                count.addProperty("value", value);
                counts.add(count);
            }
            catch (NumberFormatException tooLong) { /* not a count */ }
        }
        return counts;
    }

    /** The changes applied in order onto the latest known rows; null if nothing changed. */
    static JsonArray apply(JsonArray previous, List<Change> changes)
    {
        Map<String, JsonObject> rows = new TreeMap<>();
        if (previous != null)
            for (JsonElement row : previous)
            {
                JsonObject copy = row.getAsJsonObject().deepCopy();
                rows.put(copy.get("page").getAsString(), copy);
            }
        for (Change change : changes) change.applyTo(rows);
        markConfirmed(previous, rows, changes);
        if (rows.isEmpty() || rows.size() > MAX_ROWS) return null;
        JsonArray result = new JsonArray();
        int totalItems = 0;
        for (JsonObject row : rows.values())
        {
            totalItems += row.getAsJsonArray("items").size();
            if (totalItems > CollectionLogLayout.MAX_TOTAL_ITEMS) return null;
            result.add(row);
        }
        return result.equals(previous) ? null : result;
    }

    static ChangeAwareSourceCapture.Observation observation(List<Change> changes)
    {
        if (changes.isEmpty()) return null;
        Instant newest = changes.get(0).at;
        for (Change change : changes) if (change.at.isAfter(newest)) newest = change.at;
        List<Change> ordered = new ArrayList<>(changes);
        return ChangeAwareSourceCapture.Observation.merging(previous -> apply(previous, ordered),
            ChangeAwareSourceCapture.time(newest));
    }

    /**
     * After all of an upload's changes: an item a new-item message named that went from 0 to at least 1 is marked "confirmed", so
     * the server dates it while the game's own total lags.
     */
    private static void markConfirmed(JsonArray previous, Map<String, JsonObject> rows, List<Change> changes)
    {
        Map<String, Integer> before = new HashMap<>();
        if (previous != null)
            for (JsonElement row : previous)
                for (JsonElement item : row.getAsJsonObject().getAsJsonArray("items"))
                    before.put(row.getAsJsonObject().get("page").getAsString() + "@"
                        + item.getAsJsonObject().get("itemId").getAsInt(), item.getAsJsonObject().get("quantity").getAsInt());
        for (Change change : changes)
        {
            if (!(change instanceof NewItem)) continue;
            Integer id = ((NewItem) change).idOf(rows);
            if (id == null) continue;
            for (JsonObject row : rows.values())
                for (JsonElement item : row.getAsJsonArray("items"))
                {
                    JsonObject object = item.getAsJsonObject();
                    Integer was = before.get(row.get("page").getAsString() + "@" + id);
                    if (object.get("itemId").getAsInt() == id && object.get("quantity").getAsInt() >= 1 && was != null && was < 1)
                        object.addProperty("confirmed", true);
                }
        }
    }

    private static JsonObject item(int itemId, String name, int quantity)
    {
        JsonObject item = new JsonObject();
        item.addProperty("itemId", itemId);
        item.addProperty("name", name);
        item.addProperty("quantity", quantity);
        return item;
    }

    private static JsonObject row(String page, String tab, int pageOrder, JsonArray items,
                                  JsonElement killCounts, JsonElement bests)
    {
        JsonObject row = new JsonObject();
        row.addProperty("page", page);
        row.addProperty("tab", tab);
        row.addProperty("pageOrder", pageOrder);
        row.add("items", items);
        row.add("killCounts", killCounts == null ? JsonNull.INSTANCE : killCounts);
        row.add("bests", bests == null ? JsonNull.INSTANCE : bests);
        return row;
    }
}
