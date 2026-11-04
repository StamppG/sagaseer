package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.Arrays;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * The state.slayer row: the assignment, points, streaks and the block list, from varps, varbits and cache tables. superiorsOff is
 * the rewards switch for superiors (null when the game gives no 0 or 1).
 */
final class GameSlayer
{
    /** The task value for a boss task (the boss is in SLAYER_TARGET_BOSSID). */
    private static final int BOSS_TASK = 98;
    private static final int MAX_ID = 100_000;
    private static final int MAX_NAME = 100;
    private static final int MAX_COUNTER = 1_000_000;

    /** The cache table lookups the read needs. */
    interface Db
    {
        List<Integer> rows(int table, int column, int value);
        Object[] fields(int row, int column);
    }

    /** Block varbits by master; slot = index + 1, 7 = the diary slot. */
    private static final String[] BLOCK_MASTERS = {"Turael", "Mazchna", "Vannaka", "Chaeldar",
        "Konar", "Nieve", "Duradel", "Krystilia", "Mortimer"};
    private static final int[][] BLOCK_VARBITS = {
        {VarbitID.SLAYER_BLOCKED_TURAEL_1, VarbitID.SLAYER_BLOCKED_TURAEL_2, VarbitID.SLAYER_BLOCKED_TURAEL_3,
            VarbitID.SLAYER_BLOCKED_TURAEL_4, VarbitID.SLAYER_BLOCKED_TURAEL_5, VarbitID.SLAYER_BLOCKED_TURAEL_6,
            VarbitID.SLAYER_BLOCKED_TURAEL_DIARY},
        {VarbitID.SLAYER_BLOCKED_MAZCHNA_1, VarbitID.SLAYER_BLOCKED_MAZCHNA_2, VarbitID.SLAYER_BLOCKED_MAZCHNA_3,
            VarbitID.SLAYER_BLOCKED_MAZCHNA_4, VarbitID.SLAYER_BLOCKED_MAZCHNA_5, VarbitID.SLAYER_BLOCKED_MAZCHNA_6,
            VarbitID.SLAYER_BLOCKED_MAZCHNA_DIARY},
        {VarbitID.SLAYER_BLOCKED_VANNAKA_1, VarbitID.SLAYER_BLOCKED_VANNAKA_2, VarbitID.SLAYER_BLOCKED_VANNAKA_3,
            VarbitID.SLAYER_BLOCKED_VANNAKA_4, VarbitID.SLAYER_BLOCKED_VANNAKA_5, VarbitID.SLAYER_BLOCKED_VANNAKA_6,
            VarbitID.SLAYER_BLOCKED_VANNAKA_DIARY},
        {VarbitID.SLAYER_BLOCKED_CHAELDAR_1, VarbitID.SLAYER_BLOCKED_CHAELDAR_2, VarbitID.SLAYER_BLOCKED_CHAELDAR_3,
            VarbitID.SLAYER_BLOCKED_CHAELDAR_4, VarbitID.SLAYER_BLOCKED_CHAELDAR_5, VarbitID.SLAYER_BLOCKED_CHAELDAR_6,
            VarbitID.SLAYER_BLOCKED_CHAELDAR_DIARY},
        {VarbitID.SLAYER_BLOCKED_KONAR_1, VarbitID.SLAYER_BLOCKED_KONAR_2, VarbitID.SLAYER_BLOCKED_KONAR_3,
            VarbitID.SLAYER_BLOCKED_KONAR_4, VarbitID.SLAYER_BLOCKED_KONAR_5, VarbitID.SLAYER_BLOCKED_KONAR_6,
            VarbitID.SLAYER_BLOCKED_KONAR_DIARY},
        {VarbitID.SLAYER_BLOCKED_NIEVE_1, VarbitID.SLAYER_BLOCKED_NIEVE_2, VarbitID.SLAYER_BLOCKED_NIEVE_3,
            VarbitID.SLAYER_BLOCKED_NIEVE_4, VarbitID.SLAYER_BLOCKED_NIEVE_5, VarbitID.SLAYER_BLOCKED_NIEVE_6,
            VarbitID.SLAYER_BLOCKED_NIEVE_DIARY},
        {VarbitID.SLAYER_BLOCKED_DURADEL_1, VarbitID.SLAYER_BLOCKED_DURADEL_2, VarbitID.SLAYER_BLOCKED_DURADEL_3,
            VarbitID.SLAYER_BLOCKED_DURADEL_4, VarbitID.SLAYER_BLOCKED_DURADEL_5, VarbitID.SLAYER_BLOCKED_DURADEL_6,
            VarbitID.SLAYER_BLOCKED_DURADEL_DIARY},
        {VarbitID.SLAYER_BLOCKED_KRYSTILIA_1, VarbitID.SLAYER_BLOCKED_KRYSTILIA_2, VarbitID.SLAYER_BLOCKED_KRYSTILIA_3,
            VarbitID.SLAYER_BLOCKED_KRYSTILIA_4, VarbitID.SLAYER_BLOCKED_KRYSTILIA_5, VarbitID.SLAYER_BLOCKED_KRYSTILIA_6,
            VarbitID.SLAYER_BLOCKED_KRYSTILIA_DIARY},
        {VarbitID.SLAYER_BLOCKED_MORTIMER_1, VarbitID.SLAYER_BLOCKED_MORTIMER_2}
    };

    /** Not SLAYER_COUNT: kills left alone is no reason to upload. */
    private static final int[] VARPS = {VarPlayerID.SLAYER_TARGET,
        VarPlayerID.SLAYER_AREA, VarPlayerID.SLAYER_COUNT_ORIGINAL,
        VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED};
    private static final Set<Integer> VARP_SET = Arrays.stream(VARPS).boxed().collect(Collectors.toUnmodifiableSet());
    private static final int[] VARBITS = varbits();
    /** The same ids as a lookup: varbit changes fire many times a tick. */
    private static final Set<Integer> VARBIT_SET = Arrays.stream(VARBITS).boxed().collect(Collectors.toUnmodifiableSet());

    private GameSlayer() { }

    private static int[] varbits()
    {
        Set<Integer> ids = new HashSet<>(List.of(VarbitID.SLAYER_TARGET_BOSSID, VarbitID.SLAYER_MASTER,
            VarbitID.SLAYER_POINTS, VarbitID.SLAYER_TASKS_COMPLETED, VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED,
            VarbitID.SLAYER_TOGGLEOFF_SUPERIORMOBS));
        for (int[] master : BLOCK_VARBITS) for (int id : master) ids.add(id);
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** True when the change is one of the facts: a Slayer varp, or a Slayer varbit by its own id (RuneLite reports each packed
     * varbit's change). A shared varp changing for its other bits is not: it fired on unrelated changes in live play. */
    static boolean relevant(VarbitChanged event)
    {
        return VARP_SET.contains(event.getVarpId()) || VARBIT_SET.contains(event.getVarbitId());
    }

    /** Kills left uploads only when a task starts or ends; other kills ride along with the next upload. */
    static boolean startsOrEndsTask(int previousKillsLeft, int killsLeft)
    {
        return killsLeft == 0 || previousKillsLeft <= 0;
    }

    /** Client thread. Nothing when not logged in. */
    static Observation read(Client client, Supplier<Instant> clock)
    {
        if (client == null || client.getLocalPlayer() == null || clock == null)
            return Observation.notRead();
        return read(client::getVarpValue, client::getVarbitValue, new Db()
        {
            public List<Integer> rows(int table, int column, int value)
            { return client.getDBRowsByValue(table, column, 0, value); }
            public Object[] fields(int row, int column)
            { return client.getDBTableField(row, column, 0); }
        }, clock);
    }

    static Observation read(IntUnaryOperator varp, IntUnaryOperator varbit, Db db, Supplier<Instant> clock)
    {
        try
        {
            int remaining = varp.applyAsInt(VarPlayerID.SLAYER_COUNT);
            if (remaining < 0 || remaining > MAX_ID) return Observation.unavailable();
            JsonObject row = new JsonObject();
            row.add("assignment", remaining == 0 ? JsonNull.INSTANCE : assignment(remaining, varp, varbit, db));
            row.add("points", counter(varbit, VarbitID.SLAYER_POINTS, Integer.MAX_VALUE));
            row.add("streak", counter(varbit, VarbitID.SLAYER_TASKS_COMPLETED, MAX_COUNTER));
            row.add("wildernessStreak", counter(varbit, VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED, MAX_COUNTER));
            row.add("mortimerStreak", counter(varp, VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED, MAX_COUNTER));
            row.add("blocks", blocks(varbit, db));
            Integer superiorsOff = inRange(varbit.applyAsInt(VarbitID.SLAYER_TOGGLEOFF_SUPERIORMOBS), 0, 1);
            row.addProperty("superiorsOff", superiorsOff == null ? null : superiorsOff == 1);
            JsonArray rows = new JsonArray();
            rows.add(row);
            return new Observation(rows, ChangeAwareSourceCapture.time(clock.get()));
        }
        catch (RuntimeException unavailable)
        {
            return Observation.unavailable();
        }
    }

    /** A task id from the game must be a real id; anything else is a failed read. */
    private static JsonObject assignment(int remaining, IntUnaryOperator varp, IntUnaryOperator varbit, Db db)
    {
        int task = varp.applyAsInt(VarPlayerID.SLAYER_TARGET);
        if (task < 1 || task > MAX_ID) throw new IllegalStateException("slayer task");
        JsonObject out = new JsonObject();
        out.addProperty("taskId", task);
        Integer boss = task == BOSS_TASK ? inRange(varbit.applyAsInt(VarbitID.SLAYER_TARGET_BOSSID), 0, MAX_ID) : null;
        out.addProperty("bossId", boss);
        out.addProperty("name", task == BOSS_TASK ? bossName(boss, db) : taskName(task, db));
        out.addProperty("masterId", inRange(varbit.applyAsInt(VarbitID.SLAYER_MASTER), 1, 100));
        Integer area = inRange(varp.applyAsInt(VarPlayerID.SLAYER_AREA), 1, MAX_ID);
        out.addProperty("area", area == null ? null : areaName(area, db));
        out.addProperty("original", inRange(varp.applyAsInt(VarPlayerID.SLAYER_COUNT_ORIGINAL), 1, MAX_ID));
        out.addProperty("remaining", remaining);
        return out;
    }

    private static JsonArray blocks(IntUnaryOperator varbit, Db db)
    {
        JsonArray out = new JsonArray();
        for (int m = 0; m < BLOCK_MASTERS.length; m++)
            for (int slot = 0; slot < BLOCK_VARBITS[m].length; slot++)
            {
                Integer task = inRange(varbit.applyAsInt(BLOCK_VARBITS[m][slot]), 1, MAX_ID);
                if (task == null) continue;
                JsonObject block = new JsonObject();
                block.addProperty("master", BLOCK_MASTERS[m]);
                block.addProperty("slot", slot + 1);
                block.addProperty("taskId", task);
                block.addProperty("name", taskName(task, db));
                out.add(block);
            }
        return out;
    }

    private static JsonElement counter(IntUnaryOperator source, int id, int max)
    {
        Integer value = inRange(source.applyAsInt(id), 0, max);
        return value == null ? JsonNull.INSTANCE : new JsonPrimitive(value);
    }

    private static Integer inRange(int value, int min, int max)
    { return value >= min && value <= max ? value : null; }

    /** The row of a table whose column equals value, only when exactly one matches. */
    private static Integer row(Db db, int table, int column, int value)
    {
        try
        {
            List<Integer> rows = db.rows(table, column, value);
            return rows != null && rows.size() == 1 ? rows.get(0) : null;
        }
        catch (RuntimeException unavailable) { return null; }
    }

    private static Object field(Db db, Integer row, int column)
    {
        if (row == null) return null;
        try
        {
            Object[] values = db.fields(row, column);
            return values != null && values.length == 1 ? values[0] : null;
        }
        catch (RuntimeException unavailable) { return null; }
    }

    private static String text(Object value)
    {
        return value instanceof String && !((String) value).isBlank() && ((String) value).length() <= MAX_NAME
            ? (String) value : null;
    }

    private static String taskName(int task, Db db)
    {
        return text(field(db, row(db, DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, task),
            DBTableID.SlayerTask.COL_NAME_UPPERCASE));
    }

    /** A boss task: the sublist row gives the SlayerTask row to name. */
    private static String bossName(Integer boss, Db db)
    {
        if (boss == null) return null;
        Object task = field(db, row(db, DBTableID.SlayerTaskSublist.ID,
            DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, boss), DBTableID.SlayerTaskSublist.COL_TASK);
        return task instanceof Integer ? text(field(db, (Integer) task, DBTableID.SlayerTask.COL_NAME_UPPERCASE)) : null;
    }

    private static String areaName(int area, Db db)
    {
        return text(field(db, row(db, DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, area),
            DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER));
    }
}
