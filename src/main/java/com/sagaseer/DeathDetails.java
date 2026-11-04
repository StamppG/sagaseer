package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The state.deaths rows: the player's own recent deaths this session with when, where (the area's name only, the one exception to
 * no location; region and tiles are dropped at once) and what they were fighting, each null without direct evidence, plus the
 * carried-item change between death and respawn. The server counts deaths from the game's own counter and only adds these facts.
 */
final class DeathDetails
{
    static final int MAX_ROWS = 5;
    static final int MAX_ACCOUNTS = 10;
    static final int AREA_MAX = 60;

    private static final class Account
    {
        final List<JsonObject> rows = new ArrayList<>();
        Instant changedAt;
    }

    private final Map<String, Account> accounts = new LinkedHashMap<>(16, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Account> eldest)
        {
            return size() > MAX_ACCOUNTS;
        }
    };

    /** The plugin or the data type was turned off: nothing carries over. */
    void reset()
    {
        accounts.clear();
    }

    /** The player died; area and cause may be null. */
    String died(String account, Instant at, String area, String cause)
    {
        if (account == null || at == null) return null;
        Account own = accounts.computeIfAbsent(account, key -> new Account());
        JsonObject row = new JsonObject();
        String key = ChangeAwareSourceCapture.time(at);
        row.addProperty("at", key);
        put(row, "area", area == null || area.isEmpty() || area.length() > AREA_MAX ? null : area);
        put(row, "cause", cause);
        row.add("items", JsonNull.INSTANCE);
        own.rows.add(row);
        while (own.rows.size() > MAX_ROWS) own.rows.remove(0);
        own.changedAt = at;
        return key;
    }

    /** A completed item result replaces the matching death's items. */
    boolean items(String account, String at, DeathItems.Change change, Instant completedAt)
    {
        Account own = accounts.get(account);
        if (own == null || change == null || completedAt == null) return false;
        for (JsonObject row : own.rows)
        {
            if (!row.get("at").getAsString().equals(at)) continue;
            JsonObject items = new JsonObject();
            items.add("left", stacks(change.left));
            items.add("appeared", stacks(change.appeared));
            row.add("items", items);
            own.changedAt = completedAt;
            return true;
        }
        return false;
    }

    /** An item switch went off: no item difference is sent again. */
    void clearItems()
    {
        for (Account own : accounts.values()) for (JsonObject row : own.rows) row.add("items", JsonNull.INSTANCE);
    }

    private static JsonArray stacks(List<DeathItems.Stack> values)
    {
        JsonArray rows = new JsonArray();
        for (DeathItems.Stack value : values)
        {
            JsonObject row = new JsonObject();
            row.addProperty("id", value.id);
            row.addProperty("quantity", value.quantity);
            rows.add(row);
        }
        return rows;
    }

    /** This account's deaths, oldest first; nothing while none. */
    Observation observation(String account)
    {
        Account own = account == null ? null : accounts.get(account);
        if (own == null || own.rows.isEmpty()) return Observation.notRead();
        JsonArray value = new JsonArray();
        for (JsonObject row : own.rows) value.add(row.deepCopy());
        return new Observation(value, ChangeAwareSourceCapture.time(own.changedAt));
    }

    private static void put(JsonObject row, String key, String value)
    {
        if (value == null) row.add(key, JsonNull.INSTANCE); else row.addProperty(key, value);
    }

    /** The area's name, else "Wilderness" when the game says so, else null. */
    static String areaOf(int regionId, boolean inWilderness)
    {
        String named = DeathAreas.of(regionId);
        return named != null ? named : inWilderness ? "Wilderness" : null;
    }
}
