package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanID;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/** Direct, complete sources captured on the existing payload trigger. */
final class ChangeAwareSourceCapture
{
    private static final Set<String> SKILLS = Set.of("attack", "hitpoints", "mining",
        "strength", "agility", "smithing", "defence", "herblore", "fishing",
        "ranged", "thieving", "cooking", "prayer", "crafting", "firemaking",
        "magic", "fletching", "woodcutting", "runecraft", "slayer", "farming",
        "construction", "hunter", "sailing");

    static final class Observation
    {
        final JsonArray value;
        final String sourceObservedAt;
        final String failedReadAt;
        /** Builds the value from the latest known one, on the upload thread. */
        final UnaryOperator<JsonArray> mergeOnto;
        Observation(JsonArray value, String sourceObservedAt, String failedReadAt,
                    UnaryOperator<JsonArray> mergeOnto)
        {
            this.value = value;
            this.sourceObservedAt = sourceObservedAt;
            this.failedReadAt = failedReadAt;
            this.mergeOnto = mergeOnto;
        }
        Observation(JsonArray value, String sourceObservedAt, String failedReadAt)
        { this(value, sourceObservedAt, failedReadAt, null); }
        static Observation merging(UnaryOperator<JsonArray> mergeOnto, String sourceObservedAt)
        { return new Observation(null, sourceObservedAt, null, mergeOnto); }
        Observation(JsonArray value, String sourceObservedAt)
        { this(value, sourceObservedAt, null); }
        static Observation unavailable() { return failed(); }
        static Observation notRead() { return new Observation(null, null, null); }
        boolean observed() { return value != null; }
    }

    private ChangeAwareSourceCapture() { }

    /** The 9-digit instant uploads use. Built once (formatters are thread-safe). */
    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    static String time(Instant instant)
    { return TIME.format(instant); }

    private static Observation failed()
    { return new Observation(null, null, time(Instant.now())); }

    static Observation skills(Client client, Supplier<Instant> clock)
    {
        if (client == null || client.getLocalPlayer() == null || clock == null)
            return Observation.notRead();
        JsonArray rows = new JsonArray();
        Set<String> seen = new HashSet<>();
        try
        {
            for (Skill skill : Skill.values())
            {
                if (skill == Skill.OVERALL) continue;
                String id = skill.getName().toLowerCase(Locale.ROOT).replace(" ", "");
                if (!SKILLS.contains(id) || !seen.add(id)) return failed();
                int xp = client.getSkillExperience(skill);
                int level = client.getRealSkillLevel(skill);
                if (xp < 0 || xp > 200_000_000 || level < 1 || level > 126)
                    return failed();
                JsonObject row = new JsonObject();
                row.addProperty("skillId", id);
                row.addProperty("xp", xp);
                row.addProperty("level", level);
                rows.add(row);
            }
            if (seen.size() != SKILLS.size()) return failed();
            return new Observation(rows, time(clock.get()), null);
        }
        catch (RuntimeException unavailable) { return failed(); }
    }

    /**
     * state.account: the account type (raw 0-6), death count, Collection Log totals and membership (days left from
     * ACCOUNT_CREDIT, and member status). Not read while logged out or on Tutorial Island, where the type isn't final.
     */
    static Observation account(Client client, Supplier<Instant> clock)
    {
        if (client == null || client.getLocalPlayer() == null || clock == null)
            return Observation.notRead();
        return account(() -> client.getVarbitValue(VarbitID.IRONMAN),
            () -> client.getVarpValue(VarPlayerID.TRACKING_DEATHS),
            () -> client.getVarpValue(VarPlayerID.COLLECTION_COUNT),
            () -> client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX),
            () -> client.getVarpValue(VarPlayerID.ACCOUNT_CREDIT),
            () -> client.getWorldType() != null && client.getWorldType().contains(WorldType.MEMBERS),
            () -> client.getLocalPlayer().getWorldLocation().getRegionID(), clock);
    }

    static Observation account(IntSupplier accountType, IntSupplier deaths, IntSupplier collectionObtained,
                               IntSupplier collectionTotal, IntSupplier membershipDays, BooleanSupplier membersWorld,
                               IntSupplier regionId, Supplier<Instant> clock)
    {
        try
        {
            String observedAt = time(clock.get());
            AccountTypeResolution type = AccountTypeResolution.resolve(
                accountType.getAsInt(), regionId.getAsInt());
            if (type.getStatus() == AccountTypeResolution.Status.UNRESOLVED_ONBOARDING ||
                type.getStatus() == AccountTypeResolution.Status.PROVISIONAL_ONBOARDING)
                return Observation.notRead();
            int count = deaths.getAsInt();
            if (type.getStatus() != AccountTypeResolution.Status.RESOLVED || count < 0) return failed();
            JsonObject row = new JsonObject();
            row.addProperty("accountType", type.getRawAccountType());
            row.addProperty("deaths", count);
            // The game's own log totals: null until the game has sent them.
            int obtained = collectionObtained.getAsInt();
            int total = collectionTotal.getAsInt();
            boolean known = total > 0 && obtained >= 0 && obtained <= total;
            if (known) { row.addProperty("collectionObtained", obtained); row.addProperty("collectionTotal", total); }
            else { row.add("collectionObtained", JsonNull.INSTANCE); row.add("collectionTotal", JsonNull.INSTANCE); }
            int days = Math.max(0, membershipDays.getAsInt());
            row.addProperty("membershipDays", days);
            row.addProperty("member", days > 0 || membersWorld.getAsBoolean());
            JsonArray rows = new JsonArray();
            rows.add(row);
            return new Observation(rows, observedAt, null);
        }
        catch (RuntimeException unavailable) { return failed(); }
    }

    private static final String[] GIM_FIELDS = { "groupSize", "hardcoreDeaths", "leader", "prestiged" };
    private static final int[] GIM_VARBITS = { VarbitID.GIM_GROUPSIZE, VarbitID.GIM_HARDCORE_DEATHS,
        VarbitID.GIM_I_AM_LEADER, VarbitID.GIM_IS_PRESTIGED };

    /** GIM facts as raw varbits (zeros outside a group) and the player's own group name. Never the member list. */
    static Observation gim(Client client, Supplier<Instant> clock)
    {
        if (client == null || client.getLocalPlayer() == null || clock == null) return Observation.notRead();
        return withGroupName(varbitRow(client::getVarbitValue, clock, GIM_FIELDS, GIM_VARBITS), () -> groupName(client));
    }

    /** The group name from the clan settings, or the channel while they load. */
    private static String groupName(Client client)
    {
        ClanSettings settings = client.getClanSettings(ClanID.GROUP_IRONMAN);
        if (settings != null && settings.getName() != null) return settings.getName();
        ClanChannel channel = client.getClanChannel(ClanID.GROUP_IRONMAN);
        return channel == null ? null : channel.getName();
    }

    static final int GROUP_NAME_MAX = 32;

    /**
     * Adds groupName, or null outside a group. While the name hasn't loaded the key is left out, so a login never flips it to
     * null.
     */
    static Observation withGroupName(Observation facts, Supplier<String> name)
    {
        if (!facts.observed()) return facts;
        JsonObject row = facts.value.get(0).getAsJsonObject();
        String read;
        try { read = name.get(); }
        catch (RuntimeException unavailable) { read = null; }
        String clean = read == null ? "" : read.replace('\u00A0', ' ').trim();
        if (clean.isEmpty() && row.get("groupSize").getAsInt() > 0) return facts;
        if (clean.isEmpty() || clean.length() > GROUP_NAME_MAX || clean.chars().anyMatch(c -> c < 0x20 || c == 0x7F))
            row.add("groupName", JsonNull.INSTANCE);
        else row.addProperty("groupName", clean);
        return facts;
    }

    /**
     * state.clan: the player's own clan name and own rank (OWNER is 126). Never the member list, who is online or chat. Not read
     * until the settings arrive, unless the game has had time to send them: then outside a clan.
     */
    static Observation clan(Client client, Supplier<Instant> clock, boolean settled)
    {
        if (client == null || client.getLocalPlayer() == null || clock == null) return Observation.notRead();
        String me = client.getLocalPlayer().getName();
        return clan(() -> client.getClanSettings(ClanID.CLAN), me, clock, settled);
    }

    static Observation clan(Supplier<ClanSettings> source, String me, Supplier<Instant> clock, boolean settled)
    {
        ClanSettings settings;
        try { settings = source.get(); }
        catch (RuntimeException unavailable) { return failed(); }
        String read = settings == null ? null : settings.getName();
        String clean = read == null ? "" : read.replace(' ', ' ').trim();
        if (clean.isEmpty() && !settled) return Observation.notRead();
        JsonObject row = new JsonObject();
        if (clean.isEmpty() || clean.length() > GROUP_NAME_MAX || clean.chars().anyMatch(c -> c < 0x20 || c == 0x7F))
        {
            row.add("clanName", JsonNull.INSTANCE);
            row.add("rank", JsonNull.INSTANCE);
        }
        else
        {
            row.addProperty("clanName", clean);
            Integer rank = ownRank(settings, me);
            if (rank == null) row.add("rank", JsonNull.INSTANCE); else row.addProperty("rank", rank);
        }
        JsonArray rows = new JsonArray();
        rows.add(row);
        return new Observation(rows, time(clock.get()), null);
    }

    /** Only the player's own entry, by their own name (which may use a no-break space). */
    private static Integer ownRank(ClanSettings settings, String me)
    {
        if (me == null) return null;
        try
        {
            ClanMember mine = settings.findMember(me);
            if (mine == null) mine = settings.findMember(me.replace(' ', ' '));
            if (mine == null) mine = settings.findMember(me.replace(' ', ' '));
            int rank = mine == null || mine.getRank() == null ? Integer.MIN_VALUE : mine.getRank().getRank();
            return rank >= -1 && rank <= 127 ? rank : null;
        }
        catch (RuntimeException unavailable) { return null; }
    }

    static Observation varbitRow(Client client, Supplier<Instant> clock, String[] fields, int[] varbits)
    {
        if (client == null || client.getLocalPlayer() == null || clock == null) return Observation.notRead();
        return varbitRow(client::getVarbitValue, clock, fields, varbits);
    }

    /** One row of raw values; a negative value or exception is a failed read. */
    static Observation varbitRow(IntUnaryOperator read, Supplier<Instant> clock, String[] fields, int[] varbits)
    {
        try
        {
            String observedAt = time(clock.get());
            JsonObject row = new JsonObject();
            for (int i = 0; i < fields.length; i++)
            {
                int value = read.applyAsInt(varbits[i]);
                if (value < 0) return failed();
                row.addProperty(fields[i], value);
            }
            JsonArray rows = new JsonArray();
            rows.add(row);
            return new Observation(rows, observedAt, null);
        }
        catch (RuntimeException unavailable) { return failed(); }
    }

    /** The quest list and quest points, read together; an impossible read is a failure, never an empty list. */
    static Map<String, Observation> quests(Client client, Supplier<Instant> clock)
    {
        if (client == null || client.getLocalPlayer() == null || clock == null) return Map.of();
        return quests(Quest.values(), quest -> quest.getState(client),
            () -> client.getVarpValue(VarPlayerID.QP), clock);
    }

    static Map<String, Observation> quests(Quest[] quests, Function<Quest, QuestState> states,
                                           IntSupplier questPoints, Supplier<Instant> clock)
    {
        JsonArray rows = new JsonArray();
        int points = -1;
        try
        {
            Set<Integer> seen = new HashSet<>();
            for (Quest quest : quests)
            {
                int id = quest.getId(); // RuneLite numbers Animal Magnetism 0
                String name = quest.getName();
                QuestState state = states.apply(quest);
                if (id < 0 || !seen.add(id) || name == null || name.isEmpty() ||
                    name.length() > 100 || state == null) { rows = null; break; }
                JsonObject row = new JsonObject();
                row.addProperty("questId", id);
                row.addProperty("name", name);
                row.addProperty("state", state.name());
                rows.add(row);
            }
            if (rows != null && (rows.size() < 1 || rows.size() > 400)) rows = null;
        }
        catch (RuntimeException unavailable) { rows = null; }
        try { points = questPoints.getAsInt(); }
        catch (RuntimeException unavailable) { points = -1; }
        String observedAt;
        try { observedAt = time(clock.get()); }
        catch (RuntimeException unavailable) { observedAt = null; }
        if (rows != null) rows = sortedByQuestId(rows);
        JsonArray pointRows = null;
        if (points >= 0 && points <= 1000)
        {
            JsonObject row = new JsonObject();
            row.addProperty("questPoints", points);
            pointRows = new JsonArray();
            pointRows.add(row);
        }
        Map<String, Observation> reads = new LinkedHashMap<>();
        reads.put("state.quests", rows == null || observedAt == null ? failed() : new Observation(rows, observedAt));
        reads.put("state.questPoints", pointRows == null || observedAt == null ? failed()
            : new Observation(pointRows, observedAt));
        return reads;
    }

    private static JsonArray sortedByQuestId(JsonArray rows)
    {
        List<JsonElement> sorted = new ArrayList<>();
        for (JsonElement row : rows) sorted.add(row);
        sorted.sort(Comparator.comparingInt(row -> row.getAsJsonObject().get("questId").getAsInt()));
        JsonArray ordered = new JsonArray();
        for (JsonElement row : sorted) ordered.add(row);
        return ordered;
    }
}
