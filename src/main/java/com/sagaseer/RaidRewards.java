package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Item;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.util.Text;

/**
 * The state.raidRewards row: the player's own reward from the last raid chest opened this session (CoX, ToB, ToA, The Fractured
 * Archive), with the completion count and mode from the game's counters. Nothing about other players is kept except othersPurple:
 * someone else in the raid got a unique, read from the CoX party line (a friends-chat notification, as the CoX Censor plugin reads it)
 * or the ToA/ToB "found something special" line, with names compared and dropped.
 */
final class RaidRewards
{
    /**
     * The raids. The cache has no table of reward chests (an interface plus a container, known only as RuneLite ids), so a new
     * raid is one row here and one in COUNTERS.
     */
    enum Raid
    {
        COX("COX", "Chambers of Xeric", InterfaceID.RAIDS_REWARDS, InventoryID.RAIDS_REWARDS),
        TOB("TOB", "Theatre of Blood", InterfaceID.TOB_CHESTS, InventoryID.TOB_CHESTS),
        TOA("TOA", "Tombs of Amascut", InterfaceID.TOA_CHESTS, InventoryID.TOA_CHESTS),
        TFA("TFA", "The Fractured Archive", InterfaceID.TFA_CHESTS, InventoryID.TFA_CHESTS);

        final String code;
        final String name;
        final int interfaceId;
        final int containerId;

        Raid(String code, String name, int interfaceId, int containerId)
        {
            this.code = code;
            this.name = name;
            this.interfaceId = interfaceId;
            this.containerId = containerId;
        }
    }

    private static final class Counter
    {
        final int varp;
        final String mode;
        Counter(int varp, String mode) { this.varp = varp; this.mode = mode; }
    }

    /** The game's TOTAL_COMPLETED_* counters per mode; the one that rises names the mode. */
    private static final Map<Raid, Counter[]> COUNTERS = Map.of(
        Raid.COX, new Counter[] {
            new Counter(VarPlayerID.TOTAL_COMPLETED_XERICCHAMBERS, null),
            new Counter(VarPlayerID.TOTAL_COMPLETED_XERICCHAMBERS_CHALLENGE, "Challenge Mode")},
        Raid.TOB, new Counter[] {
            new Counter(VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD, null),
            new Counter(VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD_HARD, "Hard Mode"),
            new Counter(VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD_STORY, "Entry Mode")},
        Raid.TOA, new Counter[] {
            new Counter(VarPlayerID.TOTAL_COMPLETED_TOMBSOFAMASCUT_ENTRY, "Entry Mode"),
            new Counter(VarPlayerID.TOTAL_COMPLETED_TOMBSOFAMASCUT, null),
            new Counter(VarPlayerID.TOTAL_COMPLETED_TOMBSOFAMASCUT_EXPERT, "Expert Mode")},
        Raid.TFA, new Counter[] {new Counter(VarPlayerID.TOTAL_COMPLETED_THEFRACTUREDARCHIVE, null)});

    private static final class Rise
    {
        final String label;
        final int count;
        Rise(String label, int count) { this.label = label; this.count = count; }
    }

    private static final int MAX_ITEMS = 128;
    private static final int MAX_SETTLE_TICKS = 5;
    static final int MAX_PARTY = 100;
    /** Each raid's uniques (OSRS Wiki), lower case. */
    static final Set<String> COX_UNIQUES = Set.of("dexterous prayer scroll", "arcane prayer scroll", "twisted buckler",
        "dragon hunter crossbow", "dinh's bulwark", "ancestral hat", "ancestral robe top", "ancestral robe bottom", "dragon claws", "elder maul",
        "kodai insignia", "twisted bow");
    static final Set<String> TOA_UNIQUES = Set.of("osmumten's fang", "lightbearer", "elidinis' ward", "masori mask",
        "masori body", "masori chaps", "tumeken's shadow (uncharged)");
    static final Set<String> TOB_UNIQUES = Set.of("avernic defender hilt", "ghrazi rapier", "sanguinesti staff (uncharged)",
        "justiciar faceguard", "justiciar chestguard", "justiciar legguards", "scythe of vitur (uncharged)");
    private static final Pattern SPECIAL_LINE = Pattern.compile("^(?<name>.+?) found something special: (?<item>.+)$");
    private static final Pattern COX_LINE = Pattern.compile("^(?<name>.+?) - (?<item>.+)$");
    /** A purple this close to the chest belongs to that raid. */
    private static final Duration PURPLE_WINDOW = Duration.ofMinutes(15);

    private static final class Pending
    {
        final Raid raid;
        final long loadedTick;
        final Integer modeVarbit;
        final Integer partySize;
        List<int[]> items;

        Pending(Raid raid, long loadedTick, Integer modeVarbit, Integer partySize)
        {
            this.raid = raid;
            this.loadedTick = loadedTick;
            this.modeVarbit = modeVarbit;
            this.partySize = partySize;
        }
    }

    private Pending pending;
    private boolean looted;
    private final Map<Integer, Integer> known = new HashMap<>();
    private final Map<Raid, Rise> rises = new EnumMap<>(Raid.class);
    private Instant purpleAt;
    /** The raid a held purple came from: it only marks that raid's chest. */
    private Raid purpleRaid;
    /** A new raid began since the last chest: a purple then belongs to the next one. */
    private boolean rearmedSinceChest;
    private JsonObject latest;
    private Instant latestAt;

    static Raid raidOfInterface(int groupId)
    {
        for (Raid raid : Raid.values()) if (raid.interfaceId == groupId) return raid;
        return null;
    }

    static Raid raidOfContainer(int containerId)
    {
        for (Raid raid : Raid.values()) if (raid.containerId == containerId) return raid;
        return null;
    }

    static Integer filledSlots(int[] slots, IntUnaryOperator varbit)
    {
        int filled = 0;
        for (int slot : slots) if (varbit.applyAsInt(slot) != 0) filled++;
        return filled == 0 ? null : filled;
    }

    void reset()
    {
        pending = null;
        looted = false;
        known.clear();
        rises.clear();
        purpleAt = null;
        purpleRaid = null;
        rearmedSinceChest = false;
        latest = null;
        latestAt = null;
    }

    private static final Set<Integer> COUNTER_VARPS = counterVarps();

    private static Set<Integer> counterVarps()
    {
        Set<Integer> ids = new HashSet<>();
        for (Counter[] counters : COUNTERS.values()) for (Counter counter : counters) ids.add(counter.varp);
        return Set.copyOf(ids);
    }

    static boolean isCounter(int varpId)
    {
        return COUNTER_VARPS.contains(varpId);
    }

    /** Once per session after login settles: the counters arrive with no change event, so they are read. */
    void counterBaseline(IntUnaryOperator varp)
    {
        if (!known.isEmpty()) return;
        for (Counter[] counters : COUNTERS.values()) for (Counter counter : counters) known.put(counter.varp, varp.applyAsInt(counter.varp));
    }

    /** A rise after the baseline is a completed raid, held with its mode for that raid's next chest. */
    void counterChanged(int varpId, int value)
    {
        Integer was = known.get(varpId);
        if (was == null) return;
        known.put(varpId, value);
        if (value <= was) return;
        for (Map.Entry<Raid, Counter[]> raid : COUNTERS.entrySet())
            for (Counter counter : raid.getValue())
                if (counter.varp == varpId)
                    rises.put(raid.getKey(), new Rise(raid.getKey().name + (counter.mode == null ? "" : " " + counter.mode), value));
    }

    void rearm()
    {
        looted = false;
        rearmedSinceChest = true;
        // A purple that never reached a chest ends with its raid.
        purpleAt = null;
        purpleRaid = null;
    }

    void opened(Raid raid, long tick, Integer modeVarbit, Integer partySize, Item[] items)
    {
        if (looted || pending != null) return;
        pending = new Pending(raid, tick, modeVarbit, partySize);
        container(raid, items);
    }

    void container(Raid raid, Item[] items)
    {
        if (pending == null || pending.raid != raid || items == null) return;
        List<int[]> stacks = new ArrayList<>();
        for (Item item : items)
        {
            if (item == null || item.getId() == -1) continue;
            if (item.getId() <= 0 || item.getQuantity() <= 0)
            {
                pending.items = null;
                return;
            }
            stacks.add(new int[] {item.getId(), item.getQuantity()});
        }
        pending.items = stacks.isEmpty() || stacks.size() > MAX_ITEMS ? null : stacks;
    }

    boolean settle(long tick, Instant now, IntFunction<String> names)
    {
        if (pending == null || tick <= pending.loadedTick) return false;
        if (tick - pending.loadedTick > MAX_SETTLE_TICKS)
        {
            pending = null;
            return false;
        }
        if (pending.items == null || now == null || (latestAt != null && !now.isAfter(latestAt))) return false;
        Raid raid = pending.raid;
        JsonObject row = new JsonObject();
        row.addProperty("raid", raid.code);
        row.addProperty("name", raid.name);
        row.addProperty("observedAt", ChangeAwareSourceCapture.time(now));
        Rise rise = rises.remove(raid);
        row.add("label", rise != null ? new JsonPrimitive(rise.label) : JsonNull.INSTANCE);
        row.add("completions", rise != null ? new JsonPrimitive(rise.count) : JsonNull.INSTANCE);
        Integer mode = pending.modeVarbit;
        row.add("challengeMode", raid == Raid.COX && mode != null && (mode == 0 || mode == 1)
                ? new JsonPrimitive(mode) : JsonNull.INSTANCE);
        row.add("invocation", raid == Raid.TOA && mode != null && mode >= 0 && mode <= 600
                ? new JsonPrimitive(mode) : JsonNull.INSTANCE);
        Integer party = pending.partySize;
        row.add("partySize", party != null && party >= 1 && party <= MAX_PARTY ? new JsonPrimitive(party) : JsonNull.INSTANCE);
        row.addProperty("othersPurple", purpleAt != null && purpleRaid == raid &&
                Duration.between(purpleAt, now).abs().compareTo(PURPLE_WINDOW) <= 0);
        JsonArray items = new JsonArray();
        for (int[] stack : pending.items)
        {
            JsonObject item = new JsonObject();
            item.addProperty("id", stack[0]);
            item.addProperty("quantity", stack[1]);
            String name = names.apply(stack[0]);
            if (name == null || name.isBlank()) name = "Item " + stack[0];
            item.addProperty("name", name.length() > 100 ? name.substring(0, 100) : name);
            items.add(item);
        }
        row.add("items", items);
        latest = row;
        latestAt = now;
        purpleAt = null;
        purpleRaid = null;
        rearmedSinceChest = false;
        looted = true;
        pending = null;
        return true;
    }

    static boolean blankName(String name)
    {
        return name == null || name.replace('\u00a0', ' ').trim().isEmpty();
    }

    /** Same name, ignoring case and non-breaking spaces ("Ann" is not "Ann Marie"). */
    static boolean sameName(String a, String b)
    {
        if (a == null || b == null) return false;
        return a.replace('\u00a0', ' ').trim().toLowerCase(Locale.ROOT).equals(b.replace('\u00a0', ' ').trim().toLowerCase(Locale.ROOT));
    }

    /** One chat line: someone else's unique sets the purple flag. True when the held row changed and should upload again. */
    boolean chat(ChatMessageType type, String message, String localName, Instant at)
    {
        if (message == null || at == null || (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM
            && type != ChatMessageType.FRIENDSCHATNOTIFICATION)) return false;
        String text = Text.removeTags(message).replace(' ', ' ').trim();
        if (coxTeammateLine(type, text, localName)) return teammatePurple(Raid.COX, at);
        Raid special = specialTeammateLine(type, text, localName);
        return special != null && teammatePurple(special, at);
    }

    /** A ToA/ToB "found something special" game line naming someone else and that raid's unique, else null. */
    static Raid specialTeammateLine(ChatMessageType type, String text, String localName)
    {
        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM && type != ChatMessageType.FRIENDSCHATNOTIFICATION || text == null
            || blankName(localName)) return null;
        Matcher line = SPECIAL_LINE.matcher(text);
        if (!line.matches() || sameName(line.group("name"), localName)) return null;
        String item = line.group("item").trim().toLowerCase(Locale.ROOT);
        return TOA_UNIQUES.contains(item) ? Raid.TOA : TOB_UNIQUES.contains(item) ? Raid.TOB : null;
    }

    /** CoX's party line naming someone else and a CoX unique; a typed line is never a friends-chat notification. */
    static boolean coxTeammateLine(ChatMessageType type, String text, String localName)
    {
        // Until the player's own name is known, nothing counts.
        if (type != ChatMessageType.FRIENDSCHATNOTIFICATION || text == null || blankName(localName)) return false;
        Matcher line = COX_LINE.matcher(text);
        return line.matches() && COX_UNIQUES.contains(line.group("item").trim().toLowerCase(Locale.ROOT))
            && !sameName(line.group("name"), localName);
    }

    /**
     * Someone else got a unique: marks the chest already read if it's this raid's, no new raid began and none is waiting, within
     * 15 minutes; otherwise it's held for the raid's next chest. True when the row changed.
     */
    boolean teammatePurple(Raid raid, Instant at)
    {
        if (raid == null || at == null) return false;
        if (latest != null && pending == null && !rearmedSinceChest && raid.code.equals(latest.get("raid").getAsString())
            && !at.isBefore(latestAt) && Duration.between(latestAt, at).compareTo(PURPLE_WINDOW) <= 0)
        {
            if (latest.get("othersPurple").getAsBoolean()) return false;
            latest.addProperty("othersPurple", true);
            latestAt = at;
            return true;
        }
        purpleAt = at;
        purpleRaid = raid;
        return false;
    }

    Observation observation()
    {
        if (latest == null) return Observation.notRead();
        JsonArray rows = new JsonArray();
        rows.add(latest.deepCopy());
        return new Observation(rows, ChangeAwareSourceCapture.time(latestAt));
    }
}
