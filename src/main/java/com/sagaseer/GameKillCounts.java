package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;

/** The state.killCounts rows: one BOSS row per row of the cache table HiscoresBossesInfo, plus three LIFETIME counters. */
final class GameKillCounts
{
    private static final int MAX_NAME = 100;
    private static final int MAX_ROWS = 300;
    private static final int MAX_VARP = 100_000;

    private GameKillCounts() { }

    /** Client thread. Nothing when not logged in. */
    static Observation read(Client client, Supplier<Instant> clock)
    {
        if (client == null || client.getLocalPlayer() == null || clock == null)
            return Observation.notRead();
        return read(client::getDBTableRows, client::getDBTableField, client::getVarpValue, clock);
    }

    static Observation read(IntFunction<List<Integer>> tableRows, GameActivities.Field field,
        IntUnaryOperator varp, Supplier<Instant> clock)
    {
        try
        {
            Map<Integer, JsonObject> rows = new TreeMap<>();
            List<Integer> bossRows = tableRows.apply(DBTableID.HiscoresBossesInfo.ID);
            if (bossRows == null || bossRows.isEmpty()) return Observation.unavailable();
            for (int row : bossRows)
            {
                Object name = field.get(row, DBTableID.HiscoresBossesInfo.COL_BOSSNAME, 0)[0];
                Object id = field.get(row, DBTableID.HiscoresBossesInfo.COL_BOSSVARP, 0)[0];
                if (!(name instanceof String) || !(id instanceof Number)) return Observation.unavailable();
                add(rows, ((Number) id).intValue(), (String) name, "BOSS", varp);
            }
            add(rows, VarPlayerID.TRACKING_BOSSES_KILLED, "Bosses killed", "LIFETIME", varp);
            add(rows, VarPlayerID.TRACKING_MONSTERS_KILLED, "Monsters killed", "LIFETIME", varp);
            add(rows, VarPlayerID.TRACKING_DAMAGE_DEALT_TO_NPCS, "Damage dealt to NPCs", "LIFETIME", varp);
            if (rows.size() > MAX_ROWS) return Observation.unavailable();
            JsonArray array = new JsonArray();
            for (JsonObject row : rows.values()) array.add(row);
            return new Observation(array, ChangeAwareSourceCapture.time(clock.get()));
        }
        catch (RuntimeException unavailable)
        {
            return Observation.unavailable();
        }
    }

    /** One row per varp; impossible ids, names or values fail the read. */
    private static void add(Map<Integer, JsonObject> rows, int varpId, String name, String kind,
        IntUnaryOperator varp)
    {
        if (varpId < 1 || varpId > MAX_VARP || name.isEmpty() || name.length() > MAX_NAME)
            throw new IllegalStateException("kill counter");
        if (rows.containsKey(varpId)) return;
        int value = varp.applyAsInt(varpId);
        if (value < 0) throw new IllegalStateException("kill counter value");
        JsonObject row = new JsonObject();
        row.addProperty("varpId", varpId);
        row.addProperty("name", name);
        row.addProperty("kind", kind);
        row.addProperty("value", value);
        rows.put(varpId, row);
    }
}
