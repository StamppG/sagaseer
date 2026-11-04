package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Item;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * The state.storage rows: item containers other than inventory and worn gear. A container event is the read. The bank and GIM
 * storage are readable only while open, so their last image becomes a row when they close (the bank's also once it settles). The
 * costume room is one container, held while its screen is open in the player's own house. The rune pouch and other varbit stores
 * are read at each upload trigger, never on their own, so casting never uploads. Rows belong to one character name.
 */
final class StorageLocations
{
    enum Location
    {
        BANK(InventoryID.BANK, InterfaceID.BANKMAIN),
        SEED_VAULT(InventoryID.SEED_VAULT),
        LOOTING_BAG(InventoryID.LOOTING_BAG),
        RUNE_POUCH(-1),
        TOOL_LEPRECHAUN(-1),
        BOLT_POUCH(-1),
        SEED_BOX(InventoryID.SEED_BOX),
        TACKLE_BOX(InventoryID.TACKLE_BOX),
        FORESTRY_KIT(InventoryID.FORESTRY_KIT),
        HUNTSMANS_KIT(InventoryID.HUNTSMANS_KIT),
        COX_PRIVATE_STORAGE(InventoryID.RAIDS_PRIVATESTORAGE),
        GIM_STORAGE(InventoryID.INV_GROUP_TEMP, InterfaceID.SHARED_BANK),
        // House costume room: container 637 holds every chest (as Dude Where's My Stuff reads it), read only while the screen is
        // open with the owner's deposit panel, never from container events: visitors can see other players' chests.
        HOUSE_COSTUME_ROOM(-1, InterfaceID.POH_COSTUMES),
        // The plank sack is varbits; the five boats' cargo holds are containers 963-967.
        PLANK_SACK(-1),
        TOA_PICKAXE(-1),
        BOAT_CARGO_1(InventoryID.SAILING_BOAT_1_CARGOHOLD),
        BOAT_CARGO_2(InventoryID.SAILING_BOAT_2_CARGOHOLD),
        BOAT_CARGO_3(InventoryID.SAILING_BOAT_3_CARGOHOLD),
        BOAT_CARGO_4(InventoryID.SAILING_BOAT_4_CARGOHOLD),
        BOAT_CARGO_5(InventoryID.SAILING_BOAT_5_CARGOHOLD);

        static final int NO_CONTAINER = -1;
        /**
         * The costume room as the client keeps it: 637 plus 0x8000, one container for every chest (Dude Where's My Stuff
         * subtracts 0x8000 the same way).
         */
        static final int COSTUME_ROOM_CONTAINER = InventoryID.POH_COSTUMES + ALTERNATE_KEY;

        final int containerId;
        final int visitInterface;

        Location(int containerId)
        {
            this(containerId, 0);
        }

        Location(int containerId, int visitInterface)
        {
            this.containerId = containerId;
            this.visitInterface = visitInterface;
        }

        static Location of(int containerId)
        {
            for (Location location : values())
                if (containerId != NO_CONTAINER && (location.containerId == containerId
                    // A boat's hold can also arrive under the alternate key.
                    || location.isBoatHold() && location.containerId + ALTERNATE_KEY == containerId)) return location;
            return null;
        }

        boolean isBoatHold()
        {
            return name().startsWith("BOAT_CARGO_");
        }

        static List<Location> closedBy(int interfaceId)
        {
            List<Location> found = new ArrayList<>();
            for (Location location : values())
                if (location.visitInterface != 0 && location.visitInterface == interfaceId) found.add(location);
            return found;
        }

        boolean isBank()
        {
            return this == BANK;
        }
    }

    static final int BANK_TABS = 9;
    static final int ALTERNATE_KEY = 0x8000;

    private static final int[][] BOAT_NAMES = {
        {VarbitID.SAILING_BOAT_1_NAME_1, VarbitID.SAILING_BOAT_1_NAME_2, VarbitID.SAILING_BOAT_1_NAME_3},
        {VarbitID.SAILING_BOAT_2_NAME_1, VarbitID.SAILING_BOAT_2_NAME_2, VarbitID.SAILING_BOAT_2_NAME_3},
        {VarbitID.SAILING_BOAT_3_NAME_1, VarbitID.SAILING_BOAT_3_NAME_2, VarbitID.SAILING_BOAT_3_NAME_3},
        {VarbitID.SAILING_BOAT_4_NAME_1, VarbitID.SAILING_BOAT_4_NAME_2, VarbitID.SAILING_BOAT_4_NAME_3},
        {VarbitID.SAILING_BOAT_5_NAME_1, VarbitID.SAILING_BOAT_5_NAME_2, VarbitID.SAILING_BOAT_5_NAME_3}};
    private static final int[] NAME_WORDS = {DBTableID.SailingBoatNameOptions.Row.SAILING_BOAT_NAME_PREFIX_OPTIONS,
        DBTableID.SailingBoatNameOptions.Row.SAILING_BOAT_NAME_DESCRIPTOR_OPTIONS, DBTableID.SailingBoatNameOptions.Row.SAILING_BOAT_NAME_NOUN_OPTIONS};
    static final int BOAT_NAME_MAX = 60;

    /**
     * A boat's name from its three varbits against their word lists: 0 is the list's default, any other value the 1-based word;
     * each column is one tuple holding the whole list. Null when a value is past its list or unreadable (the site then shows
     * "Boat n").
     */
    static String boatName(Location location, IntUnaryOperator varbit, GameActivities.Field field)
    {
        if (location == null || !location.isBoatHold()) return null;
        try
        {
            int[] varbits = BOAT_NAMES[location.ordinal() - Location.BOAT_CARGO_1.ordinal()];
            List<String> words = new ArrayList<>();
            for (int part = 0; part < varbits.length; part++)
            {
                int value = varbit.applyAsInt(varbits[part]);
                if (value < 0) return null;
                Object[] list;
                try
                {
                    list = field.get(NAME_WORDS[part], value == 0 ? DBTableID.SailingBoatNameOptions.COL_DEFAULT
                        : DBTableID.SailingBoatNameOptions.COL_OPTION, 0);
                }
                catch (RuntimeException missing)
                {
                    if (value == 0) continue;
                    return null;
                }
                int word = value == 0 ? 0 : value - 1;
                if (list == null || word >= list.length) { if (value == 0) continue; return null; }
                if (!(list[word] instanceof String)) return null;
                if (!((String) list[word]).isEmpty()) words.add((String) list[word]);
            }
            String name = String.join(" ", words);
            return name.isEmpty() || name.length() > BOAT_NAME_MAX ? null : name;
        }
        catch (RuntimeException unreadable)
        {
            return null;
        }
    }

    /** The rune pouch's {rune, amount} varbits; slots 5 and 6 are the divine pouch's. */
    static final int[][] RUNE_POUCH_SLOTS = {
        {VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_QUANTITY_1}, {VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_QUANTITY_2},
        {VarbitID.RUNE_POUCH_TYPE_3, VarbitID.RUNE_POUCH_QUANTITY_3}, {VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_QUANTITY_4},
        {VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_QUANTITY_5}, {VarbitID.RUNE_POUCH_TYPE_6, VarbitID.RUNE_POUCH_QUANTITY_6}};

    private String account;
    private final Map<Location, JsonObject> changed = new EnumMap<>(Location.class);
    private final Map<Location, Item[]> images = new EnumMap<>(Location.class);
    private final Map<Location, Instant> imageAt = new EnumMap<>(Location.class);
    static final int BANK_QUIET_SECONDS = 5;
    private final Map<Location, Item[]> varbitsSeen = new EnumMap<>(Location.class);

    void reset()
    {
        account = null;
        changed.clear();
        endVisits();
    }

    void endVisits()
    {
        images.clear();
        imageAt.clear();
        varbitsSeen.clear();
    }

    /**
     * The pouch's runes via the game's rune list (0 = empty). Null when a rune can't be resolved (a partial pouch would claim too
     * little).
     */
    static Item[] runePouch(IntUnaryOperator varbit, IntUnaryOperator runeItem)
    {
        try
        {
            List<Item> runes = new ArrayList<>();
            for (int[] slot : RUNE_POUCH_SLOTS)
            {
                int rune = varbit.applyAsInt(slot[0]);
                int amount = varbit.applyAsInt(slot[1]);
                if (rune <= 0 || amount <= 0) continue;
                int itemId = runeItem.applyAsInt(rune);
                if (itemId <= 0) return null;
                runes.add(new Item(itemId, amount));
            }
            return runes.toArray(new Item[0]);
        }
        catch (RuntimeException unreadable)
        {
            return null;
        }
    }

    boolean observePouch(String character, Item[] runes, IntFunction<String> names, Instant at)
    {
        return observeVarbits(character, Location.RUNE_POUCH, runes, names, at);
    }

    boolean observeVarbits(String character, Location location, Item[] items, IntFunction<String> names, Instant at)
    {
        if (items == null || !scope(character) || Arrays.equals(items, varbitsSeen.get(location))) return false;
        if (!observe(character, location, items, names, at)) return false;
        varbitsSeen.put(location, items.clone());
        return true;
    }

    // The Tool Leprechaun: field widths from the cache, formulas as Dude Where's My Stuff and the game's script. Tools: low bit
    // plus twice EXTRA. Buckets and compost: low bits plus 32x / 256x EXTRA. Ultracompost and plant cure: the value.
    private static final int[] WATERING_CANS = {-1, ItemID.WATERING_CAN_0, ItemID.WATERING_CAN_1, ItemID.WATERING_CAN_2, ItemID.WATERING_CAN_3,
        ItemID.WATERING_CAN_4, ItemID.WATERING_CAN_5, ItemID.WATERING_CAN_6, ItemID.WATERING_CAN_7, ItemID.WATERING_CAN_8, ItemID.ZEAH_WATERINGCAN};
    /** The Wiki's capacities: a reading above them withholds the whole read, never clamps. */
    private static final int TOOLS_MAX = 100, BULK_MAX = 1000;

    static Item[] toolLeprechaun(IntUnaryOperator varbit)
    {
        try
        {
            List<Item> items = new ArrayList<>();
            int[][] tools = {{ItemID.RAKE, VarbitID.FARMING_TOOLS_RAKE, VarbitID.FARMING_TOOLS_EXTRARAKES},
                {ItemID.DIBBER, VarbitID.FARMING_TOOLS_DIBBER, VarbitID.FARMING_TOOLS_EXTRADIBBERS},
                {ItemID.SPADE, VarbitID.FARMING_TOOLS_SPADE, VarbitID.FARMING_TOOLS_EXTRASPADES},
                {varbit.applyAsInt(VarbitID.FARMING_TOOLS_FAIRYSECATEURS) == 1 ? ItemID.FAIRY_ENCHANTED_SECATEURS : ItemID.SECATEURS,
                    VarbitID.FARMING_TOOLS_SECATEURS, VarbitID.FARMING_TOOLS_EXTRASECATEURS},
                {ItemID.GARDENING_TROWEL, VarbitID.FARMING_TOOLS_TROWEL, VarbitID.FARMING_TOOLS_EXTRATROWELS}};
            for (int[] tool : tools)
                if (!add(items, tool[0], varbit.applyAsInt(tool[1]) + 2 * varbit.applyAsInt(tool[2]), TOOLS_MAX)) return null;
            int can = varbit.applyAsInt(VarbitID.FARMING_TOOLS_WATERINGCAN);
            if (can >= WATERING_CANS.length) return null;
            if (can > 0) items.add(new Item(WATERING_CANS[can], 1));
            int[][] bulk = {{ItemID.BUCKET_EMPTY, varbit.applyAsInt(VarbitID.FARMING_TOOLS_BUCKETS) + 32 * varbit.applyAsInt(VarbitID.FARMING_TOOLS_EXTRABUCKETS)
                    + 256 * varbit.applyAsInt(VarbitID.FARMING_TOOLS_EXTRA2BUCKETS)},
                {ItemID.BUCKET_COMPOST, varbit.applyAsInt(VarbitID.FARMING_TOOLS_COMPOST) + 256 * varbit.applyAsInt(VarbitID.FARMING_TOOLS_EXTRACOMPOST)},
                {ItemID.BUCKET_SUPERCOMPOST, varbit.applyAsInt(VarbitID.FARMING_TOOLS_SUPERCOMPOST) + 256 * varbit.applyAsInt(VarbitID.FARMING_TOOLS_EXTRASUPERCOMPOST)},
                {ItemID.BUCKET_ULTRACOMPOST, varbit.applyAsInt(VarbitID.FARMING_TOOLS_ULTRACOMPOST)},
                {ItemID.PLANT_CURE, varbit.applyAsInt(VarbitID.FARMING_TOOLS_PLANTCURE)}};
            for (int[] row : bulk) if (!add(items, row[0], row[1], BULK_MAX)) return null;
            int bucket = varbit.applyAsInt(VarbitID.FARMING_TOOLS_BOTTOMLESS_BUCKET_TYPE);
            if (bucket > 4) return null;
            if (bucket > 0) items.add(new Item(bucket == 1 ? ItemID.BOTTOMLESS_COMPOST_BUCKET : ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED, 1));
            return items.toArray(new Item[0]);
        }
        catch (RuntimeException unreadable)
        {
            return null;
        }
    }

    // The bolt pouch: four slots of type and count; the type list is Dude Where's My Stuff's (BoltPouch.java, BSD-2, Thource).
    private static final int[] BOLT_TYPES = {-1, ItemID.BOLT, ItemID.XBOWS_CROSSBOW_BOLTS_BLURITE, ItemID.XBOWS_CROSSBOW_BOLTS_IRON,
        ItemID.XBOWS_CROSSBOW_BOLTS_STEEL, ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL, ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE,
        ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE, ItemID.XBOWS_CROSSBOW_BOLTS_SILVER,
        ItemID.POISON_BOLT, ItemID.XBOWS_CROSSBOW_BOLTS_BLURITE_POISONED, ItemID.XBOWS_CROSSBOW_BOLTS_IRON_POISONED,
        ItemID.XBOWS_CROSSBOW_BOLTS_STEEL_POISONED, ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_POISONED, ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_POISONED,
        ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_POISONED, ItemID.XBOWS_CROSSBOW_BOLTS_SILVER_POISONED,
        ItemID.POISON_BOLT_, ItemID.XBOWS_CROSSBOW_BOLTS_BLURITE_POISONED_, ItemID.XBOWS_CROSSBOW_BOLTS_IRON_POISONED_,
        ItemID.XBOWS_CROSSBOW_BOLTS_STEEL_POISONED_, ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_POISONED_, ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_POISONED_,
        ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_POISONED_, ItemID.XBOWS_CROSSBOW_BOLTS_SILVER_POISONED_,
        ItemID.POISON_BOLT__, ItemID.XBOWS_CROSSBOW_BOLTS_BLURITE_POISONED__, ItemID.XBOWS_CROSSBOW_BOLTS_IRON_POISONED__,
        ItemID.XBOWS_CROSSBOW_BOLTS_STEEL_POISONED__, ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_POISONED__, ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_POISONED__,
        ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_POISONED__, ItemID.XBOWS_CROSSBOW_BOLTS_SILVER_POISONED__,
        ItemID.OPAL_BOLT, ItemID.XBOWS_CROSSBOW_BOLTS_BLURITE_TIPPED_JADE, ItemID.PEARL_BOLT, ItemID.XBOWS_CROSSBOW_BOLTS_STEEL_TIPPED_REDTOPAZ,
        ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_TIPPED_SAPPHIRE, ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_TIPPED_EMERALD,
        ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_TIPPED_RUBY, ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_TIPPED_DIAMOND,
        ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_TIPPED_DRAGONSTONE, ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_TIPPED_ONYX,
        ItemID.XBOWS_CROSSBOW_BOLTS_BRONZE_TIPPED_OPAL_ENCHANTED, ItemID.XBOWS_CROSSBOW_BOLTS_BLURITE_TIPPED_JADE_ENCHANTED,
        ItemID.XBOWS_CROSSBOW_BOLTS_IRON_TIPPED_PEARL_ENCHANTED, ItemID.XBOWS_CROSSBOW_BOLTS_STEEL_TIPPED_REDTOPAZ_ENCHANTED,
        ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_TIPPED_SAPPHIRE_ENCHANTED, ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_TIPPED_EMERALD_ENCHANTED,
        ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_TIPPED_RUBY_ENCHANTED, ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_TIPPED_DIAMOND_ENCHANTED,
        ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_TIPPED_DRAGONSTONE_ENCHANTED, ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_TIPPED_ONYX_ENCHANTED,
        ItemID.XBOWS_GRAPPLE_TIP_BOLT_MITHRIL_ROPE, ItemID.BARBED_BOLT, ItemID.DTTD_BONE_CROSSBOW_BOLT,
        ItemID.SLAYER_BROAD_BOLT, ItemID.SLAYER_BROAD_BOLT_AMETHYST,
        ItemID.DRAGON_BOLTS, ItemID.DRAGON_BOLTS_P, ItemID.DRAGON_BOLTS_P_, ItemID.DRAGON_BOLTS_P__,
        ItemID.DRAGON_BOLTS_UNENCHANTED_OPAL, ItemID.DRAGON_BOLTS_UNENCHANTED_JADE, ItemID.DRAGON_BOLTS_UNENCHANTED_PEARL,
        ItemID.DRAGON_BOLTS_UNENCHANTED_TOPAZ, ItemID.DRAGON_BOLTS_UNENCHANTED_SAPPHIRE, ItemID.DRAGON_BOLTS_UNENCHANTED_EMERALD,
        ItemID.DRAGON_BOLTS_UNENCHANTED_RUBY, ItemID.DRAGON_BOLTS_UNENCHANTED_DIAMOND, ItemID.DRAGON_BOLTS_UNENCHANTED_DRAGONSTONE,
        ItemID.DRAGON_BOLTS_UNENCHANTED_ONYX,
        ItemID.DRAGON_BOLTS_ENCHANTED_OPAL, ItemID.DRAGON_BOLTS_ENCHANTED_JADE, ItemID.DRAGON_BOLTS_ENCHANTED_PEARL,
        ItemID.DRAGON_BOLTS_ENCHANTED_TOPAZ, ItemID.DRAGON_BOLTS_ENCHANTED_SAPPHIRE, ItemID.DRAGON_BOLTS_ENCHANTED_EMERALD,
        ItemID.DRAGON_BOLTS_ENCHANTED_RUBY, ItemID.DRAGON_BOLTS_ENCHANTED_DIAMOND, ItemID.DRAGON_BOLTS_ENCHANTED_DRAGONSTONE,
        ItemID.DRAGON_BOLTS_ENCHANTED_ONYX, ItemID.BARROWS_KARIL_AMMO};
    private static final int BOLTS_MAX = 10_000;

    /** The bolt pouch's items. Null for an unknown type or a count past capacity: withheld, never guessed. */
    static Item[] boltPouch(IntUnaryOperator varbit)
    {
        try
        {
            int[] types = {VarbitID.XBOWS_POUCH_SLOT1, VarbitID.XBOWS_POUCH_SLOT2, VarbitID.XBOWS_POUCH_SLOT3, VarbitID.XBOWS_POUCH_SLOT4};
            int[] counts = {VarbitID.XBOWS_POUCH_NUM1, VarbitID.XBOWS_POUCH_NUM2, VarbitID.XBOWS_POUCH_NUM3, VarbitID.XBOWS_POUCH_NUM4};
            List<Item> items = new ArrayList<>();
            for (int slot = 0; slot < types.length; slot++)
            {
                int type = varbit.applyAsInt(types[slot]), count = varbit.applyAsInt(counts[slot]);
                if (type < 0 || type >= BOLT_TYPES.length) return null;
                if (type == 0) { if (count < 0 || count > BOLTS_MAX) return null; continue; }
                if (!add(items, BOLT_TYPES[type], count, BOLTS_MAX)) return null;
            }
            return items.toArray(new Item[0]);
        }
        catch (RuntimeException unreadable)
        {
            return null;
        }
    }

    // The plank sack (unconfirmed in game): one varbit per wood; an implausible count withholds the read.
    private static final int PLANKS_MAX = 10_000;

    static Item[] plankSack(IntUnaryOperator varbit)
    {
        try
        {
            int[][] planks = {{ItemID.WOODPLANK, VarbitID.PLANK_SACK_PLAIN}, {ItemID.PLANK_OAK, VarbitID.PLANK_SACK_OAK},
                {ItemID.PLANK_TEAK, VarbitID.PLANK_SACK_TEAK}, {ItemID.PLANK_MAHOGANY, VarbitID.PLANK_SACK_MAHOGANY},
                {ItemID.PLANK_CAMPHOR, VarbitID.PLANK_SACK_CAMPHOR}, {ItemID.PLANK_IRONWOOD, VarbitID.PLANK_SACK_IRONWOOD},
                {ItemID.PLANK_ROSEWOOD, VarbitID.PLANK_SACK_ROSEWOOD}};
            List<Item> items = new ArrayList<>();
            for (int[] plank : planks) if (!add(items, plank[0], varbit.applyAsInt(plank[1]), PLANKS_MAX)) return null;
            return items.toArray(new Item[0]);
        }
        catch (RuntimeException unreadable)
        {
            return null;
        }
    }

    private static final int TOA_STORED_DRAGON_PICKAXE = 8;

    /**
     * The ToA pickaxe, empty with none; an unseen value is sent as empty too, so an old pickaxe stops being claimed. Null only
     * when unreadable.
     */
    static Item[] toaPickaxe(IntUnaryOperator varbit)
    {
        try
        {
            int stored = varbit.applyAsInt(VarbitID.TOA_PICKAXE_STORED);
            return stored == TOA_STORED_DRAGON_PICKAXE ? new Item[] {new Item(ItemID.DRAGON_PICKAXE, 1)} : new Item[0];
        }
        catch (RuntimeException unreadable)
        {
            return null;
        }
    }

    boolean sentVarbits(Location location)
    {
        return varbitsSeen.containsKey(location);
    }

    private static boolean add(List<Item> items, int itemId, int count, int max)
    {
        if (count < 0 || count > max) return false;
        if (count > 0) items.add(new Item(itemId, count));
        return true;
    }

    /** An empty seed box sends no container, so its Check line is the read: a game message, plain or SPAM, never player chat. */
    static boolean emptySeedBox(ChatMessageType type, String message)
    {
        return (type == ChatMessageType.GAMEMESSAGE || type == ChatMessageType.SPAM) && "The seed box is empty.".equals(message);
    }

    boolean observe(String character, Location location, Item[] items, IntFunction<String> names, Instant at)
    {
        return observe(character, location, items, names, null, at);
    }

    boolean observeHold(String character, Location location, Item[] items, IntFunction<String> names, String boatName, Instant at)
    {
        if (!scope(character) || location == null || items == null || names == null || at == null) return false;
        JsonObject row = row(location, items, names, null, id -> false, at);
        if (row == null) return false;
        if (boatName != null && location.isBoatHold() && boatName.length() <= BOAT_NAME_MAX) row.addProperty("name", boatName);
        changed.put(location, row);
        return true;
    }

    /**
     * The open bank becomes a row once it stops changing for BANK_QUIET_SECONDS, not only on close: a logout or hop with the bank
     * open would lose the visit. Group storage waits for its close.
     */
    boolean bankSettled(Instant now)
    {
        Instant at = imageAt.get(Location.BANK);
        return at != null && !now.isBefore(at.plusSeconds(BANK_QUIET_SECONDS));
    }

    void visitSeen(String character, Location location, Item[] items, Instant at)
    {
        if (!scope(character) || location == null || items == null || at == null) return;
        images.put(location, items.clone());
        imageAt.put(location, at);
    }

    boolean visitClosed(String character, Location location, int[] tabs, IntFunction<String> names, IntPredicate placeholder)
    {
        if (!scope(character) || location == null || !images.containsKey(location)) return false;
        return observe(character, location, images.remove(location), names, tabs, placeholder, imageAt.remove(location));
    }

    Observation take(String character)
    {
        if (character == null || !character.equals(account) || changed.isEmpty()) return null;
        List<JsonObject> rows = new ArrayList<>(changed.values());
        changed.clear();
        String newest = null;
        for (JsonObject row : rows)
        {
            String at = row.get("observedAt").getAsString();
            if (newest == null || at.compareTo(newest) > 0) newest = at;
        }
        return Observation.merging(previous -> merge(previous, rows), newest);
    }

    static JsonArray merge(JsonArray previous, List<JsonObject> rows)
    {
        JsonArray merged = new JsonArray();
        if (previous != null)
            for (JsonElement kept : previous)
            {
                boolean replaced = false;
                for (JsonObject row : rows)
                    replaced |= row.get("location").equals(kept.getAsJsonObject().get("location"));
                if (!replaced) merged.add(kept);
            }
        for (JsonObject row : rows) merged.add(row);
        return merged;
    }

    private boolean observe(String character, Location location, Item[] items, IntFunction<String> names,
                            int[] tabs, Instant at)
    {
        return observe(character, location, items, names, tabs, id -> false, at);
    }

    private boolean observe(String character, Location location, Item[] items, IntFunction<String> names,
                            int[] tabs, IntPredicate placeholder, Instant at)
    {
        if (!scope(character) || location == null || items == null || names == null || at == null) return false;
        JsonObject row = row(location, items, names, tabs, placeholder, at);
        if (row == null) return false;
        changed.put(location, row);
        return true;
    }

    private boolean scope(String character)
    {
        if (character == null || character.isBlank()) return false;
        if (!character.equals(account))
        {
            reset();
            account = character;
        }
        return true;
    }

    private static JsonObject row(Location location, Item[] items, IntFunction<String> names,
                                  int[] tabs, IntPredicate placeholder, Instant at)
    {
        boolean bank = location.isBank();
        JsonArray rows = new JsonArray();
        // A boat's hold can be unstacked in game: the same item is one stack here, as the game shows by default.
        Map<Integer, JsonObject> stacks = location.isBoatHold() ? new LinkedHashMap<>() : null;
        try
        {
            for (Item item : items)
            {
                if (item == null || item.getId() <= 0) continue;
                // A bank placeholder keeps its place empty; elsewhere it isn't an item.
                boolean empty = item.getQuantity() <= 0 || placeholder.test(item.getId());
                if (empty && !bank) continue;
                JsonObject stack = stacks == null ? null : stacks.get(item.getId());
                if (stack != null)
                {
                    stack.addProperty("quantity", Math.addExact(stack.get("quantity").getAsInt(), item.getQuantity()));
                    continue;
                }
                String name = names.apply(item.getId());
                if (name == null || name.isEmpty()) return null;
                JsonObject entry = new JsonObject();
                entry.addProperty("id", item.getId());
                entry.addProperty("quantity", empty ? 0 : item.getQuantity());
                entry.addProperty("name", name);
                rows.add(entry);
                if (stacks != null) stacks.put(item.getId(), entry);
            }
        }
        catch (RuntimeException unreadable)
        {
            return null;
        }
        JsonObject row = new JsonObject();
        row.addProperty("location", location.name());
        row.addProperty("observedAt", ChangeAwareSourceCapture.time(at));
        if (bank)
        {
            if (tabs == null || tabs.length != BANK_TABS) return null;
            JsonArray sizes = new JsonArray();
            for (int size : tabs) sizes.add(size);
            row.add("tabs", sizes);
        }
        else row.add("tabs", JsonNull.INSTANCE);
        row.add("items", rows);
        return row;
    }
}
