package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.Arrays;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

final class AchievementDiaryState
{
    private static final int COMPLETION_INFO_SCRIPT = 2200;

    private static final String[] REGION_NAMES = {
        "ardougne",
        "desert",
        "falador",
        "fremennik",
        "kandarin",
        "karamja",
        "kourend",
        "lumbridge",
        "morytania",
        "varrock",
        "western",
        "wilderness"
    };

    private static final int[] REGION_SCRIPT_IDS = {
        1, 5, 2, 3, 4, 0, 11, 6, 7, 8, 10, 9
    };


    private static final int[][] PACKED_REGION_VARPS = {
        {VarPlayerID.ARDOUNGE_ACHIEVEMENT_DIARY, VarPlayerID.ARDOUNGE_ACHIEVEMENT_DIARY2},
        {VarPlayerID.DESERT_ACHIEVEMENT_DIARY, VarPlayerID.DESERT_ACHIEVEMENT_DIARY2},
        {VarPlayerID.FALADOR_ACHIEVEMENT_DIARY, VarPlayerID.FALADOR_ACHIEVEMENT_DIARY2},
        {VarPlayerID.FREMENNIK_ACHIEVEMENT_DIARY, VarPlayerID.FREMENNIK_ACHIEVEMENT_DIARY2},
        {VarPlayerID.KANDARIN_ACHIEVEMENT_DIARY, VarPlayerID.KANDARIN_ACHIEVEMENT_DIARY2},
        {VarPlayerID.ATJUN_TASKS_4},
        {VarPlayerID.KOUREND_ACHIEVEMENT_DIARY, VarPlayerID.KOUREND_ACHIEVEMENT_DIARY2},
        {VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY, VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY2},
        {VarPlayerID.MORYTANIA_ACHIEVEMENT_DIARY, VarPlayerID.MORYTANIA_ACHIEVEMENT_DIARY2},
        {VarPlayerID.VARROCK_ACHIEVEMENT_DIARY, VarPlayerID.VARROCK_ACHIEVEMENT_DIARY2},
        {VarPlayerID.WESTERN_ACHIEVEMENT_DIARY, VarPlayerID.WESTERN_ACHIEVEMENT_DIARY2},
        {VarPlayerID.WILDERNESS_ACHIEVEMENT_DIARY, VarPlayerID.WILDERNESS_ACHIEVEMENT_DIARY2}
    };

    private static final int[][] KARAMJA_TASK_VARBITS = {
        {
            VarbitID.ATJUN_EASY_BANANA,
            VarbitID.ATJUN_EASY_SWING,
            VarbitID.ATJUN_EASY_GOLD,
            VarbitID.ATJUN_EASY_BOAT_SARIM,
            VarbitID.ATJUN_EASY_BOAT_ARDY,
            VarbitID.ATJUN_EASY_CAIRN,
            VarbitID.ATJUN_EASY_FISHING,
            VarbitID.ATJUN_EASY_SEAWEED,
            VarbitID.ATJUN_EASY_TZHAAR,
            VarbitID.ATJUN_EASY_JOGRE
        },
        {
            VarbitID.ATJUN_MED_AGILITY,
            VarbitID.ATJUN_MED_VOLCANO,
            VarbitID.ATJUN_MED_CRANDOR,
            VarbitID.ATJUN_MED_CART,
            VarbitID.ATJUN_MED_CLEANUP,
            VarbitID.ATJUN_MED_SPIDER,
            VarbitID.ATJUN_MED_KHAZARD,
            VarbitID.ATJUN_MED_TEAK,
            VarbitID.ATJUN_MED_MAHOGANY,
            VarbitID.ATJUN_MED_KARAMBWAN,
            VarbitID.ATJUN_MED_MACHETTE,
            VarbitID.ATJUN_MED_GLIDER,
            VarbitID.ATJUN_MED_FARMING,
            VarbitID.ATJUN_MED_GRAAHK,
            VarbitID.ATJUN_MED_SHILO_VINES,
            VarbitID.ATJUN_MED_SHILO_LAVA,
            VarbitID.ATJUN_MED_SHILO_STAIRS,
            VarbitID.ATJUN_MED_CHARTER,
            VarbitID.ATJUN_MED_TOPAZ
        },
        {
            VarbitID.ATJUN_HARD_FIGHTPITS,
            VarbitID.ATJUN_HARD_FIGHTCAVE,
            VarbitID.ATJUN_HARD_OOMLIE,
            VarbitID.ATJUN_HARD_NATURE,
            VarbitID.ATJUN_HARD_KARAMBWAN,
            VarbitID.ATJUN_HARD_DEATHWING,
            VarbitID.ATJUN_HARD_XBOW,
            VarbitID.ATJUN_HARD_PALM,
            VarbitID.ATJUN_HARD_DURADEL,
            VarbitID.ATJUN_HARD_DRAGON
        }
    };

    private AchievementDiaryState()
    {
    }

    /** Diary task-word varps, as a lookup. */
    private static final Set<Integer> DIARY_VARPS = diaryVarps();

    private static Set<Integer> diaryVarps()
    {
        Set<Integer> ids = new HashSet<>();
        ids.add(VarPlayerID.KOUREND_ACHIEVEMENT_DIARY_MULTISTAGE);
        for (int[] words : PACKED_REGION_VARPS) for (int word : words) ids.add(word);
        return Set.copyOf(ids);
    }

    static boolean isDiaryVarp(int varpId)
    {
        return DIARY_VARPS.contains(varpId);
    }

    /** All 12 regions, read on the client thread. */
    static Observation read(Client client, Supplier<Instant> clock)
    {
        if (client == null || client.getLocalPlayer() == null || clock == null)
            return Observation.notRead();
        return read(client::getVarpValue, client::getVarbitValue, region ->
        {
            client.runScript(COMPLETION_INFO_SCRIPT, region);
            int[] stack = client.getIntStack();
            // Copy now: the next region's script reuses the same stack.
            return client.getIntStackSize() >= 12 && stack != null && stack.length >= 12
                ? Arrays.copyOf(stack, 12) : null;
        }, clock);
    }

    /** completionInfo: the script's first 12 values for a region, or null; may throw. */
    static Observation read(IntUnaryOperator varp, IntUnaryOperator varbit,
            IntFunction<int[]> completionInfo, Supplier<Instant> clock)
    {
        try
        {
            JsonArray rows = new JsonArray();
            for (int region = 0; region < REGION_NAMES.length; region++)
            {
                JsonObject row = new JsonObject();
                row.addProperty("region", REGION_NAMES[region]);
                JsonArray words = new JsonArray();
                for (int word : PACKED_REGION_VARPS[region])
                    words.add(Integer.toUnsignedLong(varp.applyAsInt(word)));
                row.add("words", words);
                JsonArray extra = new JsonArray();
                if (REGION_NAMES[region].equals("karamja"))
                    for (int[] tier : KARAMJA_TASK_VARBITS)
                        for (int id : tier) extra.add(varbit.applyAsInt(id));
                else if (REGION_NAMES[region].equals("kourend"))
                    extra.add(Integer.toUnsignedLong(
                        varp.applyAsInt(VarPlayerID.KOUREND_ACHIEVEMENT_DIARY_MULTISTAGE)));
                row.add("extraValues", extra);
                row.add("tierCounts", tierCounts(completionInfo, REGION_SCRIPT_IDS[region]));
                rows.add(row);
            }
            return new Observation(rows, ChangeAwareSourceCapture.time(clock.get()));
        }
        catch (RuntimeException unavailable)
        {
            return Observation.unavailable();
        }
    }

    /** Tier counts are a cross-check: a failed script is null for that region and never blocks the task words. */
    private static JsonElement tierCounts(IntFunction<int[]> completionInfo,
            int scriptRegion)
    {
        try
        {
            int[] stack = completionInfo.apply(scriptRegion);
            if (stack == null || stack.length < 12) return JsonNull.INSTANCE;
            JsonArray tiers = new JsonArray();
            for (int tier = 0; tier < 4; tier++)
            {
                int completed = Math.max(0, stack[tier * 3]);
                int total = Math.max(0, stack[tier * 3 + 1]);
                if (completed > total) return JsonNull.INSTANCE;
                JsonObject counts = new JsonObject();
                counts.addProperty("completed", completed);
                counts.addProperty("total", total);
                tiers.add(counts);
            }
            return tiers;
        }
        catch (RuntimeException ignored)
        {
            return JsonNull.INSTANCE;
        }
    }
}
