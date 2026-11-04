package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The state.logAnnouncements rows: each log item the game announced to one account, with its moment. The list outlives a logout
 * or hop; each account has its own (at most MAX_ACCOUNTS). The server uses it to date an item the plugin saw drop, even before
 * the first log sync. The item id comes from the log layout by name; null when ambiguous.
 */
final class LogAnnouncements
{
    static final int MAX_ROWS = 50;
    static final int MAX_ACCOUNTS = 10;

    /** Each account's (player name's) rows, the least recently used first. */
    private final Map<String, List<JsonObject>> lists = new LinkedHashMap<>(16, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<JsonObject>> eldest)
        {
            return size() > MAX_ACCOUNTS;
        }
    };

    /** The plugin or the data type was turned off: nothing carries over. */
    void reset()
    {
        lists.clear();
    }

    void announced(String account, String name, Instant at, CollectionLogLayout layout)
    {
        String trimmed = name == null ? "" : name.trim();
        if (account == null || trimmed.isEmpty() || trimmed.length() > 100) return;
        List<JsonObject> rows = lists.computeIfAbsent(account, key -> new ArrayList<>());
        JsonObject row = new JsonObject();
        Integer itemId = idOf(trimmed, layout);
        if (itemId == null) row.add("itemId", JsonNull.INSTANCE); else row.addProperty("itemId", itemId);
        row.addProperty("at", ChangeAwareSourceCapture.time(at));
        rows.add(row);
        while (rows.size() > MAX_ROWS) rows.remove(0);
    }

    /** This account's announcements, oldest first; nothing while none. */
    Observation observation(String account)
    {
        List<JsonObject> rows = account == null ? null : lists.get(account);
        if (rows == null || rows.isEmpty()) return Observation.notRead();
        JsonArray value = new JsonArray();
        for (JsonObject row : rows) value.add(row.deepCopy());
        return new Observation(value, rows.get(rows.size() - 1).get("at").getAsString());
    }

    /** The one log item id with this name; null without a layout, a match, or with several. */
    static Integer idOf(String name, CollectionLogLayout layout)
    {
        if (layout == null) return null;
        String wanted = name.toLowerCase(Locale.ROOT);
        Integer found = null;
        for (CollectionLogLayout.Page page : layout.pages)
            for (int i = 0; i < page.itemIds.length; i++)
                if (page.itemNames[i] != null && page.itemNames[i].trim().toLowerCase(Locale.ROOT).equals(wanted))
                {
                    if (found != null && found != page.itemIds[i]) return null;
                    found = page.itemIds[i];
                }
        return found;
    }
}
