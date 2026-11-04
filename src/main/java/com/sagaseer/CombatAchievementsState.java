package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/** The state.combatAchievements row: raw task words, tier counts, points and this account's reward thresholds. */
final class CombatAchievementsState
{
    /** Task id = word * 32 + bit. */
    static final int[] TASK_WORD_VARPS = {
        VarPlayerID.CA_TASK_COMPLETED_0,
        VarPlayerID.CA_TASK_COMPLETED_1,
        VarPlayerID.CA_TASK_COMPLETED_2,
        VarPlayerID.CA_TASK_COMPLETED_3,
        VarPlayerID.CA_TASK_COMPLETED_4,
        VarPlayerID.CA_TASK_COMPLETED_5,
        VarPlayerID.CA_TASK_COMPLETED_6,
        VarPlayerID.CA_TASK_COMPLETED_7,
        VarPlayerID.CA_TASK_COMPLETED_8,
        VarPlayerID.CA_TASK_COMPLETED_9,
        VarPlayerID.CA_TASK_COMPLETED_10,
        VarPlayerID.CA_TASK_COMPLETED_11,
        VarPlayerID.CA_TASK_COMPLETED_12,
        VarPlayerID.CA_TASK_COMPLETED_13,
        VarPlayerID.CA_TASK_COMPLETED_14,
        VarPlayerID.CA_TASK_COMPLETED_15,
        VarPlayerID.CA_TASK_COMPLETED_16,
        VarPlayerID.CA_TASK_COMPLETED_17,
        VarPlayerID.CA_TASK_COMPLETED_18,
        VarPlayerID.CA_TASK_COMPLETED_19,
        VarPlayerID.CA_TASK_COMPLETED_20
    };

    private static final int[] TIER_COUNT_VARBITS = {
        VarbitID.CA_TOTAL_TASKS_COMPLETED_EASY,
        VarbitID.CA_TOTAL_TASKS_COMPLETED_MEDIUM,
        VarbitID.CA_TOTAL_TASKS_COMPLETED_HARD,
        VarbitID.CA_TOTAL_TASKS_COMPLETED_ELITE,
        VarbitID.CA_TOTAL_TASKS_COMPLETED_MASTER,
        VarbitID.CA_TOTAL_TASKS_COMPLETED_GRANDMASTER
    };

    /** Points each tier needs for this account (Group Ironmen need fewer). */
    private static final int[] THRESHOLD_VARBITS = {
        VarbitID.CA_THRESHOLD_EASY,
        VarbitID.CA_THRESHOLD_MEDIUM,
        VarbitID.CA_THRESHOLD_HARD,
        VarbitID.CA_THRESHOLD_ELITE,
        VarbitID.CA_THRESHOLD_MASTER,
        VarbitID.CA_THRESHOLD_GRANDMASTER
    };

    private static final int MAX_POINTS = 100_000;

    private CombatAchievementsState()
    {
    }

    /** Read on the client thread; nothing when not logged in. */
    static Observation read(Client client, Supplier<Instant> clock)
    {
        if (client == null || client.getLocalPlayer() == null || clock == null)
            return Observation.notRead();
        return read(client::getVarpValue, client::getVarbitValue, clock);
    }

    static Observation read(IntUnaryOperator varp, IntUnaryOperator varbit, Supplier<Instant> clock)
    {
        try
        {
            JsonArray words = new JsonArray();
            for (int word : TASK_WORD_VARPS) words.add(Integer.toUnsignedLong(varp.applyAsInt(word)));
            JsonArray tiers = new JsonArray();
            for (int tier : TIER_COUNT_VARBITS)
            {
                int count = varbit.applyAsInt(tier);
                if (count < 0) return Observation.unavailable();
                tiers.add(count);
            }
            int points = varbit.applyAsInt(VarbitID.CA_POINTS);
            JsonObject row = new JsonObject();
            row.add("taskWords", words);
            row.add("tierCounts", tiers);
            // Out of the server's range: no points rather than a bad section.
            if (points >= 0 && points <= MAX_POINTS) row.addProperty("points", points);
            else row.add("points", JsonNull.INSTANCE);
            row.add("thresholds", thresholds(varbit));
            JsonArray rows = new JsonArray();
            rows.add(row);
            return new Observation(rows, ChangeAwareSourceCapture.time(clock.get()));
        }
        catch (RuntimeException unavailable)
        {
            return Observation.unavailable();
        }
    }

    /** The thresholds, or null unless each is positive, rising and in range. */
    private static JsonElement thresholds(IntUnaryOperator varbit)
    {
        JsonArray thresholds = new JsonArray();
        int previous = 0;
        for (int id : THRESHOLD_VARBITS)
        {
            int points = varbit.applyAsInt(id);
            if (points <= previous || points > MAX_POINTS) return JsonNull.INSTANCE;
            thresholds.add(points);
            previous = points;
        }
        return thresholds;
    }
}
