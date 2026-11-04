package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import com.google.gson.JsonElement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * The state.activities rows: the game's minigame and activity counters from varps and varbits. A fixed list named in RuneLite's
 * API, then every other row of the cache table HiscoresActivityInfo (except League Points and Collections Logged), plus a
 * CLUE:<TIER> row per tier the casket line has named.
 */
final class GameActivities
{
    private static final int MAX_NAME = 100;
    private static final int MAX_ROWS = 400;
    private static final int MAX_ID = 999_999;
    /** The bank potion store's group, shown as hidden storage. */
    static final String POTION_STORE = "Potion storage";
    private static final List<String> SKIPPED_TABLE_ROWS = List.of("league points", "collections logged");
    /** The game's line when a casket is opened: "You have completed 25 medium Treasure Trails." */
    private static final Pattern CLUE_LINE = Pattern.compile(
        "You have completed ([0-9][0-9,]{0,12}) (beginner|easy|medium|hard|elite|master) Treasure Trails?[.]");

    private static final class Counter
    {
        final boolean varbit;
        final int id;
        final String activity;
        final String name;
        final String kind;
        /** Zeros left out (the potion store's 113 potions are mostly 0). */
        final boolean skipZero;

        Counter(boolean varbit, int id, String activity, String name, String kind)
        {
            this(varbit, id, activity, name, kind, false);
        }

        Counter(boolean varbit, int id, String activity, String name, String kind, boolean skipZero)
        {
            this.varbit = varbit;
            this.id = id;
            this.activity = activity;
            this.name = name;
            this.kind = kind;
            this.skipZero = skipZero;
        }
    }

    private static Counter varp(int id, String activity, String name) { return new Counter(false, id, activity, name, "COUNT"); }
    private static Counter varbit(int id, String activity, String name) { return new Counter(true, id, activity, name, "COUNT"); }
    private static Counter varpPoints(int id, String activity, String name) { return new Counter(false, id, activity, name, "POINTS"); }
    private static Counter varbitPoints(int id, String activity, String name) { return new Counter(true, id, activity, name, "POINTS"); }
    private static Counter potion(int varp, String name) { return new Counter(false, varp, POTION_STORE, name, "COUNT", true); }

    /**
     * Only counters the game keeps loaded. COLLECTION_*_COMPLETED are page-complete flags, not counts. Per-tier clue counts come
     * from the casket line.
     */
    private static final List<Counter> FIXED = List.of(
        varp(VarPlayerID.COMPLETED_CLUES_TOTAL, "Clue scrolls", "Completed (all tiers)"),
        varbit(VarbitID.BARBASSAULT_GAMBLECOUNT, "Barbarian Assault", "Gambles"),
        varp(VarPlayerID.CASTLEWARS_WINS, "Castle Wars", "Wins"),
        varp(VarPlayerID.CASTLEWARS_LOSS, "Castle Wars", "Losses"),
        varp(VarPlayerID.CASTLEWARS_GAMES_PLAYED, "Castle Wars", "Games played"),
        varpPoints(VarPlayerID.SOUL_WARS_ZEAL, "Soul Wars", "Zeal"),
        varp(VarPlayerID.SOUL_WARS_TOTAL_KILLS, "Soul Wars", "Kills"),
        varp(VarPlayerID.SOUL_WARS_TOTAL_DEATHS, "Soul Wars", "Deaths"),
        varp(VarPlayerID.SOUL_WARS_TOTAL_GAMES, "Soul Wars", "Games played"),
        varp(VarPlayerID.SOUL_WARS_TOTAL_WINS, "Soul Wars", "Wins"),
        varp(VarPlayerID.TOTAL_GOTR_KILLS, "Guardians of the Rift", "Rifts closed"),
        varp(VarPlayerID.TOTAL_TEMPOROSS_KILLS, "Tempoross", "Kills"),
        varp(VarPlayerID.TOTAL_WINTERTODT_KILLS, "Wintertodt", "Kills"),
        varp(VarPlayerID.TOTAL_ZALCANO_KILLS, "Zalcano", "Kills"),
        varpPoints(VarPlayerID.NZONE_REWARDPOINTS, "Nightmare Zone", "Reward points"),
        varbitPoints(VarbitID.HOSIDIUS_TITHE_REWARDPOINTS, "Tithe Farm", "Reward points"),
        varpPoints(VarPlayerID.GIANTS_FOUNDRY_REWARD_SHOP_POINTS, "Giants' Foundry", "Reward points"),
        varpPoints(VarPlayerID.COLOSSEUM_GLORY, "Fortis Colosseum", "Glory"),
        varbit(VarbitID.COLOSSEUM_HIGHEST_WAVE, "Fortis Colosseum", "Highest wave"),
        // Kingdom approval as kept (0-127) and the coffer in gp.
        varbit(VarbitID.MISC_APPROVAL, "Kingdom of Miscellania", "Approval"),
        varbit(VarbitID.MISC_COFFERS, "Kingdom of Miscellania", "Coffer"),
        // Tears of Guthix: quest points at the last visit, and the best run.
        varbit(VarbitID.TOG_QP_BEFORE_RETURN, "Tears of Guthix", "Quest points at last visit"),
        varbit(VarbitID.TOG_MAX_TEARS_COLLECTED, "Tears of Guthix", "Most tears collected"),
        // Mixology points, and Barbarian Assault's base and extra role points, raw (the joining formula isn't known).
        varpPoints(VarPlayerID.MIXOLOGY_LYE_POINTS, "Mastering Mixology", "Lye points"),
        varpPoints(VarPlayerID.MIXOLOGY_AGA_POINTS, "Mastering Mixology", "Aga points"),
        varpPoints(VarPlayerID.MIXOLOGY_MOX_POINTS, "Mastering Mixology", "Mox points"),
        varbitPoints(VarbitID.BARBASSAULT_POINTS_ATTACKER_BASE, "Barbarian Assault", "Attacker points (base)"),
        varbitPoints(VarbitID.BARBASSAULT_POINTS_ATTACKER_EXTRA, "Barbarian Assault", "Attacker points (extra)"),
        varbitPoints(VarbitID.BARBASSAULT_POINTS_COLLECTOR_BASE, "Barbarian Assault", "Collector points (base)"),
        varbitPoints(VarbitID.BARBASSAULT_POINTS_COLLECTOR_EXTRA, "Barbarian Assault", "Collector points (extra)"),
        varbitPoints(VarbitID.BARBASSAULT_POINTS_HEALER_BASE, "Barbarian Assault", "Healer points (base)"),
        varbitPoints(VarbitID.BARBASSAULT_POINTS_HEALER_EXTRA, "Barbarian Assault", "Healer points (extra)"),
        varbitPoints(VarbitID.BARBASSAULT_POINTS_DEFENDER_BASE, "Barbarian Assault", "Defender points (base)"),
        varbitPoints(VarbitID.BARBASSAULT_POINTS_DEFENDER_EXTRA, "Barbarian Assault", "Defender points (extra)"),
        // The Farming Guild contract's three varbits, raw; the Backend reads their meaning.
        varbit(VarbitID.FARMGUILD_CONTRACT_TYPE, "Farming Guild contract", "Type"),
        varbit(VarbitID.FARMGUILD_CONTRACT_COUNT, "Farming Guild contract", "Contracts done"),
        varbit(VarbitID.FARMGUILD_CONTRACT_COMPLETE, "Farming Guild contract", "Complete"),
        // The NZ and Blast Furnace coffers, in gp.
        varbit(VarbitID.NZONE_CASH, "Nightmare Zone", "Coffer"),
        varbit(VarbitID.BLAST_FURNACE_COFFER, "Blast Furnace", "Coffer"),
        // Hidden storage: raw values grouped by place; the Backend names and shows them on Bank & Storage.
        varbit(VarbitID.TOA_PICKAXE_STORED, "Tombs of Amascut pickaxe", "Pickaxe stored"),
        varbit(VarbitID.TOA_PICKAXE_CHARGES, "Tombs of Amascut pickaxe", "Pickaxe charges"),
        // Potion storage: POTIONSTORE_<POTION> counts, raw (not the VILE_SIZE varbits, which are withdraw sizes). Zeros are left
        // out.
        potion(VarPlayerID.POTIONSTORE_VIALS, "Vials"),
        potion(VarPlayerID.POTIONSTORE_AGILITY_POTION, "Agility potion"),
        potion(VarPlayerID.POTIONSTORE_ANCIENT_BREW, "Ancient brew"),
        potion(VarPlayerID.POTIONSTORE_ANTI_VENOM, "Anti-venom"),
        potion(VarPlayerID.POTIONSTORE_ANTI_VENOM_P, "Anti-venom+"),
        potion(VarPlayerID.POTIONSTORE_ANTIDOTE_P, "Antidote+"),
        potion(VarPlayerID.POTIONSTORE_ANTIDOTE_PP, "Antidote++"),
        potion(VarPlayerID.POTIONSTORE_ANTIFIRE_POTION, "Antifire potion"),
        potion(VarPlayerID.POTIONSTORE_ANTIPOISON, "Antipoison"),
        potion(VarPlayerID.POTIONSTORE_ATTACK_POTION, "Attack potion"),
        potion(VarPlayerID.POTIONSTORE_BASTION_POTION, "Bastion potion"),
        potion(VarPlayerID.POTIONSTORE_BATTLEMAGE_POTION, "Battlemage potion"),
        potion(VarPlayerID.POTIONSTORE_COMBAT_POTION, "Combat potion"),
        potion(VarPlayerID.POTIONSTORE_COMPOST_POTION, "Compost potion"),
        potion(VarPlayerID.POTIONSTORE_DEFENCE_POTION, "Defence potion"),
        potion(VarPlayerID.POTIONSTORE_DIVINE_BASTION_POTION, "Divine bastion potion"),
        potion(VarPlayerID.POTIONSTORE_DIVINE_BATTLEMAGE_POTION, "Divine battlemage potion"),
        potion(VarPlayerID.POTIONSTORE_DIVINE_MAGIC_POTION, "Divine magic potion"),
        potion(VarPlayerID.POTIONSTORE_DIVINE_RANGING_POTION, "Divine ranging potion"),
        potion(VarPlayerID.POTIONSTORE_DIVINE_SUPER_ATTACK_POTION, "Divine super attack potion"),
        potion(VarPlayerID.POTIONSTORE_DIVINE_SUPER_COMBAT_POTION, "Divine super combat potion"),
        potion(VarPlayerID.POTIONSTORE_DIVINE_SUPER_DEFENCE_POTION, "Divine super defence potion"),
        potion(VarPlayerID.POTIONSTORE_DIVINE_SUPER_STRENGTH_POTION, "Divine super strength potion"),
        potion(VarPlayerID.POTIONSTORE_ENERGY_POTION, "Energy potion"),
        potion(VarPlayerID.POTIONSTORE_EXTENDED_ANTIFIRE_POTION, "Extended antifire potion"),
        potion(VarPlayerID.POTIONSTORE_EXTENDED_SUPER_ANTIFIRE_POTION, "Extended super antifire potion"),
        potion(VarPlayerID.POTIONSTORE_FISHING_POTION, "Fishing potion"),
        potion(VarPlayerID.POTIONSTORE_FORGOTTENBREW_POTION, "Forgotten brew"),
        potion(VarPlayerID.POTIONSTORE_GOADING_POTION, "Goading potion"),
        potion(VarPlayerID.POTIONSTORE_GUTHIX_BALANCE_POTION, "Guthix balance"),
        potion(VarPlayerID.POTIONSTORE_HUNTER_POTION, "Hunter potion"),
        potion(VarPlayerID.POTIONSTORE_MAGICESSENCE_POTION, "Magic essence"),
        potion(VarPlayerID.POTIONSTORE_MAGIC_POTION, "Magic potion"),
        potion(VarPlayerID.POTIONSTORE_MENAPHITEREMEDY_POTION, "Menaphite remedy"),
        potion(VarPlayerID.POTIONSTORE_PRAYER_POTION, "Prayer potion"),
        potion(VarPlayerID.POTIONSTORE_RANGING_POTION, "Ranging potion"),
        potion(VarPlayerID.POTIONSTORE_RELICYMSBALM_POTION, "Relicym's balm"),
        potion(VarPlayerID.POTIONSTORE_RESTORE_POTION, "Restore potion"),
        potion(VarPlayerID.POTIONSTORE_SANFEWSERUM_POTION, "Sanfew serum"),
        potion(VarPlayerID.POTIONSTORE_SARADOMINBREW_POTION, "Saradomin brew"),
        potion(VarPlayerID.POTIONSTORE_SERUM207_POTION, "Serum 207"),
        potion(VarPlayerID.POTIONSTORE_STAMINA_POTION, "Stamina potion"),
        potion(VarPlayerID.POTIONSTORE_STRENGTH_POTION, "Strength potion"),
        potion(VarPlayerID.POTIONSTORE_SUPER_ANTIFIRE_POTION, "Super antifire potion"),
        potion(VarPlayerID.POTIONSTORE_SUPER_ATTACK_POTION, "Super attack potion"),
        potion(VarPlayerID.POTIONSTORE_SUPER_COMBAT_POTION, "Super combat potion"),
        potion(VarPlayerID.POTIONSTORE_SUPER_DEFENCE_POTION, "Super defence potion"),
        potion(VarPlayerID.POTIONSTORE_SUPER_ENERGY_POTION, "Super energy potion"),
        potion(VarPlayerID.POTIONSTORE_SUPER_RESTORE_POTION, "Super restore potion"),
        potion(VarPlayerID.POTIONSTORE_SUPER_STRENGTH_POTION, "Super strength potion"),
        potion(VarPlayerID.POTIONSTORE_SUPER_ANTIPOISON_POTION, "Super antipoison potion"),
        potion(VarPlayerID.POTIONSTORE_WEAPON_POISON_POTION, "Weapon poison potion"),
        potion(VarPlayerID.POTIONSTORE_WEAPON_POISON_P_POTION, "Weapon poison+"),
        potion(VarPlayerID.POTIONSTORE_WEAPON_POISON_PP_POTION, "Weapon poison++"),
        potion(VarPlayerID.POTIONSTORE_ZAMORAK_BREW_POTION, "Zamorak brew"),
        potion(VarPlayerID.POTIONSTORE_PRAYER_REGENERATION_POTION, "Prayer regeneration potion"),
        potion(VarPlayerID.POTIONSTORE_ATTACK_MIX, "Attack mix"),
        potion(VarPlayerID.POTIONSTORE_ANTIPOISON_MIX, "Antipoison mix"),
        potion(VarPlayerID.POTIONSTORE_RELICYMS_MIX, "Relicym's mix"),
        potion(VarPlayerID.POTIONSTORE_STRENGTH_MIX, "Strength mix"),
        potion(VarPlayerID.POTIONSTORE_RESTORE_MIX, "Restore mix"),
        potion(VarPlayerID.POTIONSTORE_ENERGY_MIX, "Energy mix"),
        potion(VarPlayerID.POTIONSTORE_DEFENCE_MIX, "Defence mix"),
        potion(VarPlayerID.POTIONSTORE_AGILITY_MIX, "Agility mix"),
        potion(VarPlayerID.POTIONSTORE_COMBAT_MIX, "Combat mix"),
        potion(VarPlayerID.POTIONSTORE_PRAYER_MIX, "Prayer mix"),
        potion(VarPlayerID.POTIONSTORE_SUPERATTACK_MIX, "Super attack mix"),
        potion(VarPlayerID.POTIONSTORE_ANTIPOISON_SUPER_MIX, "Super antipoison mix"),
        potion(VarPlayerID.POTIONSTORE_FISHING_MIX, "Fishing mix"),
        potion(VarPlayerID.POTIONSTORE_SUPER_ENERGY_MIX, "Super energy mix"),
        potion(VarPlayerID.POTIONSTORE_HUNTING_MIX, "Hunting mix"),
        potion(VarPlayerID.POTIONSTORE_SUPER_STRENGTH_MIX, "Super strength mix"),
        potion(VarPlayerID.POTIONSTORE_MAGIC_ESSENCE_MIX, "Magic essence mix"),
        potion(VarPlayerID.POTIONSTORE_SUPER_RESTORE_MIX, "Super restore mix"),
        potion(VarPlayerID.POTIONSTORE_SUPER_DEFENCE_MIX, "Super defence mix"),
        potion(VarPlayerID.POTIONSTORE_ANTIDOTE_P_MIX, "Antidote+ mix"),
        potion(VarPlayerID.POTIONSTORE_ANTIFIRE_MIX, "Antifire mix"),
        potion(VarPlayerID.POTIONSTORE_RANGING_MIX, "Ranging mix"),
        potion(VarPlayerID.POTIONSTORE_MAGIC_MIX, "Magic mix"),
        potion(VarPlayerID.POTIONSTORE_ZAMORAK_MIX, "Zamorak mix"),
        potion(VarPlayerID.POTIONSTORE_STAMINA_MIX, "Stamina mix"),
        potion(VarPlayerID.POTIONSTORE_EXTENDED_ANTIFIRE_MIX, "Extended antifire mix"),
        potion(VarPlayerID.POTIONSTORE_ANCIENT_MIX, "Ancient mix"),
        potion(VarPlayerID.POTIONSTORE_SUPER_ANTIFIRE_MIX, "Super antifire mix"),
        potion(VarPlayerID.POTIONSTORE_EXTENDED_SUPER_ANTIFIRE_MIX, "Extended super antifire mix"),
        potion(VarPlayerID.POTIONSTORE_GUAMVIAL, "Guam vial"),
        potion(VarPlayerID.POTIONSTORE_MARRENTILLVIAL, "Marrentill vial"),
        potion(VarPlayerID.POTIONSTORE_TARROMINVIAL, "Tarromin vial"),
        potion(VarPlayerID.POTIONSTORE_HARRALANDERVIAL, "Harralander vial"),
        potion(VarPlayerID.POTIONSTORE_RANARRVIAL, "Ranarr vial"),
        potion(VarPlayerID.POTIONSTORE_TOADFLAXVIAL, "Toadflax vial"),
        potion(VarPlayerID.POTIONSTORE_IRITVIAL, "Irit vial"),
        potion(VarPlayerID.POTIONSTORE_AVANTOEVIAL, "Avantoe vial"),
        potion(VarPlayerID.POTIONSTORE_KWUARMVIAL, "Kwuarm vial"),
        potion(VarPlayerID.POTIONSTORE_SNAPDRAGONVIAL, "Snapdragon vial"),
        potion(VarPlayerID.POTIONSTORE_CADANTINEVIAL, "Cadantine vial"),
        potion(VarPlayerID.POTIONSTORE_LANTADYMEVIAL, "Lantadyme vial"),
        potion(VarPlayerID.POTIONSTORE_DWARFWEEDVIAL, "Dwarfweed vial"),
        potion(VarPlayerID.POTIONSTORE_TORSTOLVIAL, "Torstol vial"),
        potion(VarPlayerID.POTIONSTORE_BLIGHTED_2RESTORE_POTION, "Blighted super restore"),
        potion(VarPlayerID.POTIONSTORE_EXTENDED_ANTIVENOMPLUS_POTION, "Extended anti-venom+"),
        potion(VarPlayerID.POTIONSTORE_CADANTINE_BLOODVIAL, "Cadantine blood vial"),
        potion(VarPlayerID.POTIONSTORE_HUASCAVIAL, "Huasca vial"),
        potion(VarPlayerID.POTIONSTORE_SURGE_POTION, "Surge potion"),
        potion(VarPlayerID.POTIONSTORE_ARMADYLBREW_POTION, "Armadyl brew"),
        potion(VarPlayerID.POTIONSTORE_EXTENDED_STAMINA_POTION, "Extended stamina potion"),
        potion(VarPlayerID.POTIONSTORE_EXTREME_ENERGY_POTION, "Extreme energy potion"),
        potion(VarPlayerID.POTIONSTORE_SUPER_FISHING_POTION, "Super fishing potion"),
        potion(VarPlayerID.POTIONSTORE_SUPER_HUNTER_POTION, "Super hunter potion"),
        potion(VarPlayerID.POTIONSTORE_ELKHORNVIAL, "Elkhorn vial"),
        potion(VarPlayerID.POTIONSTORE_PILLARVIAL, "Pillar vial"),
        potion(VarPlayerID.POTIONSTORE_UMBRALVIAL, "Umbral vial"),
        potion(VarPlayerID.POTIONSTORE_HAEMOSTATIC_POULTICE, "Haemostatic poultice"));

    interface Field
    {
        Object[] get(int row, int column, int tuple);
    }

    private GameActivities() { }

    static Observation read(Client client, Supplier<Instant> clock)
    {
        if (client == null || client.getLocalPlayer() == null || clock == null)
            return Observation.notRead();
        return read(client::getDBTableRows, client::getDBTableField, client::getVarpValue,
            client::getVarbitValue, clock);
    }

    static Observation read(IntFunction<List<Integer>> tableRows, Field field, IntUnaryOperator varp,
        IntUnaryOperator varbit, Supplier<Instant> clock)
    {
        try
        {
            List<Integer> tableRowIds = tableRows.apply(DBTableID.HiscoresActivityInfo.ID);
            if (tableRowIds == null || tableRowIds.isEmpty()) return Observation.unavailable();
            Map<String, JsonObject> rows = new TreeMap<>();
            for (Counter counter : FIXED)
                add(rows, counter.varbit, counter.id, counter.activity, counter.name, counter.kind,
                    counter.varbit ? varbit : varp, counter.skipZero);
            for (int row : tableRowIds)
            {
                Object name = field.get(row, DBTableID.HiscoresActivityInfo.COL_ACTIVITYNAME, 0)[0];
                Object id = field.get(row, DBTableID.HiscoresActivityInfo.COL_ACTIVITYVARP, 0)[0];
                if (!(name instanceof String) || !(id instanceof Number)) return Observation.unavailable();
                if (SKIPPED_TABLE_ROWS.contains(((String) name).toLowerCase(Locale.ROOT))) continue;
                add(rows, false, ((Number) id).intValue(), (String) name, (String) name, "COUNT", varp, false);
            }
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

    /** Clue tier counts the chat named since the last upload. */
    static final class ClueChat
    {
        private final Map<String, Integer> pending = new TreeMap<>();

        /** The completed-clue line; its count is kept for the next upload. */
        boolean accept(String message)
        {
            Matcher line = CLUE_LINE.matcher(message == null ? "" : message.trim());
            if (!line.matches()) return false;
            try
            {
                int count = Integer.parseInt(line.group(1).replace(",", ""));
                pending.put(line.group(2).toUpperCase(Locale.ROOT), count);
                return true;
            }
            catch (NumberFormatException tooLong) { return false; }
        }

        void reset() { pending.clear(); }
    }

    /** The fresh read with CLUE rows kept and the chat's new counts applied, only when the read worked. */
    static Observation withClues(Observation fresh, ClueChat chat)
    {
        if (!fresh.observed()) return fresh;
        Map<String, Integer> named = new TreeMap<>(chat.pending);
        chat.reset();
        return Observation.merging(previous -> {
            Map<String, JsonObject> rows = new TreeMap<>();
            for (JsonElement row : fresh.value) rows.put(row.getAsJsonObject().get("key").getAsString(), row.getAsJsonObject());
            if (previous != null)
                for (JsonElement row : previous)
                {
                    String key = row.getAsJsonObject().get("key").getAsString();
                    if (key.startsWith("CLUE:")) rows.put(key, row.getAsJsonObject());
                }
            for (Map.Entry<String, Integer> tier : named.entrySet())
            {
                JsonObject row = new JsonObject();
                row.addProperty("key", "CLUE:" + tier.getKey());
                row.addProperty("activity", "Clue scrolls");
                row.addProperty("name", tier.getKey().charAt(0) + tier.getKey().substring(1).toLowerCase(Locale.ROOT));
                row.addProperty("kind", "COUNT");
                row.addProperty("value", tier.getValue());
                rows.put("CLUE:" + tier.getKey(), row);
            }
            JsonArray merged = new JsonArray();
            for (JsonObject row : rows.values()) merged.add(row);
            return merged;
        }, fresh.sourceObservedAt);
    }

    /** Juna's rule (OSRS Wiki): a quest point, or this much XP, since the last visit. */
    static final int STORY_XP = 100_000;

    /**
     * Tears of Guthix visits. No readable week timer exists, but a visit ends when TOG_TEARS_COLLECTED drops back to 0; that
     * moment and the total XP are kept.
     */
    static final class Tears
    {
        private int collected = -1;
        private Instant visitAt;
        private long visitXp;

        /** True when this change ended a visit. */
        boolean changed(int value, Instant at, long totalXp)
        {
            boolean ended = collected > 0 && value == 0;
            collected = value;
            if (ended)
            {
                visitAt = at;
                visitXp = totalXp;
            }
            return ended;
        }

        void reset()
        {
            collected = -1;
            visitAt = null;
        }
    }

    /**
     * The TOG rows kept and a new visit applied: TOG:LAST_VISIT (Unix seconds), TOG:XP_AT_VISIT and TOG:STORIES, whether Juna's
     * rule is met now, worked out here so the XP itself never uploads at every drop.
     */
    static Observation withTears(Observation merged, Tears tears, int questPoints, long totalXp)
    {
        if (merged.mergeOnto == null) return merged;
        Instant visitAt = tears.visitAt;
        long visitXp = tears.visitXp;
        tears.visitAt = null;
        return Observation.merging(previous -> {
            Map<String, JsonObject> rows = new TreeMap<>();
            for (JsonElement row : merged.mergeOnto.apply(previous)) rows.put(row.getAsJsonObject().get("key").getAsString(), row.getAsJsonObject());
            String recorded = "VARBIT:" + VarbitID.TOG_QP_BEFORE_RETURN;
            JsonObject before = null;
            if (previous != null)
                for (JsonElement row : previous)
                {
                    String key = row.getAsJsonObject().get("key").getAsString();
                    if (key.equals("TOG:LAST_VISIT") || key.equals("TOG:XP_AT_VISIT")) rows.put(key, row.getAsJsonObject());
                    if (key.equals(recorded)) before = row.getAsJsonObject();
                }
            // New quest points recorded for a visit SagaSeer didn't see: the kept visit is no longer the last, so it goes.
            JsonObject now = rows.get(recorded);
            if (visitAt == null && before != null && now != null && before.get("value").getAsInt() != now.get("value").getAsInt())
            {
                rows.remove("TOG:LAST_VISIT");
                rows.remove("TOG:XP_AT_VISIT");
            }
            if (visitAt != null)
            {
                rows.put("TOG:LAST_VISIT", tearsRow("TOG:LAST_VISIT", "Last visit", "TIME", visitAt.getEpochSecond()));
                if (visitXp >= 0 && visitXp <= Integer.MAX_VALUE)
                    rows.put("TOG:XP_AT_VISIT", tearsRow("TOG:XP_AT_VISIT", "Total XP at last visit", "COUNT", visitXp));
                else rows.remove("TOG:XP_AT_VISIT");
            }
            JsonObject qp = rows.get("VARBIT:" + VarbitID.TOG_QP_BEFORE_RETURN), xp = rows.get("TOG:XP_AT_VISIT");
            if (rows.containsKey("TOG:LAST_VISIT") && qp != null && xp != null && questPoints >= 0 && totalXp >= 0)
            {
                boolean stories = questPoints > qp.get("value").getAsInt() || totalXp - xp.get("value").getAsLong() >= STORY_XP;
                rows.put("TOG:STORIES", tearsRow("TOG:STORIES", "New stories for Juna", "FLAG", stories ? 1 : 0));
            }
            JsonArray result = new JsonArray();
            for (JsonObject row : rows.values()) result.add(row);
            return result;
        }, merged.sourceObservedAt);
    }

    private static JsonObject tearsRow(String key, String name, String kind, long value)
    {
        JsonObject row = new JsonObject();
        row.addProperty("key", key);
        row.addProperty("activity", "Tears of Guthix");
        row.addProperty("name", name);
        row.addProperty("kind", kind);
        row.addProperty("value", value);
        return row;
    }

    /** One row per counter; impossible ids or names fail the read, negative values are left out. */
    private static void add(Map<String, JsonObject> rows, boolean varbit, int id, String activity, String name,
        String kind, IntUnaryOperator source, boolean skipZero)
    {
        if (id < 1 || id > MAX_ID || activity.isEmpty() || activity.length() > MAX_NAME
            || name.isEmpty() || name.length() > MAX_NAME)
            throw new IllegalStateException("activity counter");
        String key = (varbit ? "VARBIT:" : "VARP:") + id;
        if (rows.containsKey(key)) return;
        int value = source.applyAsInt(id);
        if (value < 0 || skipZero && value == 0) return;
        JsonObject row = new JsonObject();
        row.addProperty("key", key);
        row.addProperty("activity", activity);
        row.addProperty("name", name);
        row.addProperty("kind", kind);
        row.addProperty("value", value);
        rows.put(key, row);
    }
}
