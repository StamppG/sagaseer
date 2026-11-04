package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GroundObject;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * The state.house row: the player's own house layout, read once per visit, and the house settings, read at every upload. Own
 * house only: a visit counts only after the player's own way in and the house loading within 30 ticks (the entry rule of the
 * Plugin Hub's POH Clue Teleports, HouseContext.java, BSD-2-Clause); building mode is always their own. The layout goes up raw:
 * room chunks from the instance template, and each room's named objects with exact copies (a multi-tile object counts once, at
 * its south-west tile; template bounds as the Hub's reimagined-world-map reads them, BSD-2-Clause). Also the portal nexus's
 * Teleport menu texts while it is open there.
 */
final class HouseCapture
{
    static final int ENTRY_WINDOW_TICKS = 30;
    static final int SETTLE_TICKS = 2;
    /** RuneLite's Discord plugin REGION_POH, as POH Clue Teleports uses it. */
    private static final Set<Integer> HOUSE_REGIONS = Set.of(7534, 7535, 7790, 7791, 8046, 8047, 8302, 8303);
    /** The menagerie's item pets, kept in the client's 0x8000 namespace like the costume room. */
    static final int MENAGERIE_CONTAINER = InventoryID.POH_MENAGERIE_PETS;
    static final int MENAGERIE_PET_ENUM = 985;
    private static final int MAX_OBJECTS_PER_ROOM = 200;
    private static final int MAX_NEXUS_TEXTS = 60;
    private static final int MAX_NEXUS_TEXT_LENGTH = 60;
    /** A menu row's key, as in "1 : Barrows"; two characters in case a long nexus counts past 9. */
    private static final Pattern NEXUS_KEY = Pattern.compile("^[0-9A-Za-z]{1,2}\\s*:\\s*");

    private boolean ownHouseConfirmed;
    private int entryTick = -1;
    private boolean entrySawLoading;
    private int outsideAfterLoadingTick = -1;
    private int ownSince = -1;
    private boolean scanned;
    private JsonObject pending;
    private Instant pendingAt;
    private JsonArray nexusShown;
    private JsonArray pendingNexus;
    private Instant pendingNexusAt;

    static boolean isTemplate(int x, int y)
    {
        return x >= 1856 && x <= 2047 && y >= 7040 && y <= 7119;
    }

    void onGameStateChanged(GameState gameState)
    {
        if (gameState == GameState.LOADING)
        {
            ownHouseConfirmed = false;
            ownSince = -1;
            scanned = false;
            if (entryTick >= 0) entrySawLoading = true;
        }
        else if (gameState != GameState.LOGGED_IN)
        {
            reset();
        }
    }

    void onMenuOptionClicked(MenuOptionClicked event, Client client)
    {
        if (event == null || client == null || client.getGameState() != GameState.LOGGED_IN) return;
        MenuAction action = event.getMenuAction();
        String option = plain(event.getMenuOption());
        String target = plain(event.getMenuTarget());
        boolean objectAction = isObjectAction(action);
        boolean widgetAction = isWidgetAction(action);
        boolean spell = widgetAction && event.getParam1() == InterfaceID.MagicSpellbook.TELEPORT_TO_YOUR_HOUSE;
        String dialog = "";
        if (action == MenuAction.WIDGET_CONTINUE && (event.getParam1() >>> 16) == InterfaceID.CHATMENU)
        {
            Widget widget = event.getWidget();
            dialog = widget == null ? "" : plain(widget.getText());
        }
        if ((objectAction && ("friend's house".equals(option) || "house advertisement".equals(target)))
            || (spell && option.startsWith("group:")) || dialog.contains("friend's house"))
        {
            // Another player's house: the entry ends, but a read of the player's own house still waiting to upload stays.
            ownHouseConfirmed = false;
            clearEntry();
            return;
        }
        boolean portal = objectAction && "portal".equals(target) && ("home".equals(option) || "build mode".equals(option));
        boolean tablet = (isItemAction(action) || widgetAction) && "break".equals(option)
            && (event.getItemId() == ItemID.POH_TABLET_TELEPORTTOHOUSE
                || (isItemAction(action) && event.getId() == ItemID.POH_TABLET_TELEPORTTOHOUSE));
        boolean cape = (isItemAction(action) || widgetAction) && "tele to poh".equals(option)
            && (event.getItemId() == ItemID.SKILLCAPE_CONSTRUCTION || event.getItemId() == ItemID.SKILLCAPE_CONSTRUCTION_TRIMMED);
        boolean ownDialog = "go to your house.".equals(dialog) || "go to your house".equals(dialog)
            || "go to your house (building mode).".equals(dialog);
        // The fairy ring code for the house (A, I, Q), as RuneLite's FairyRingPlugin maps the dials.
        boolean fairyHouse = widgetAction && event.getParam1() == InterfaceID.Fairyrings.CONFIRM && event.getId() == 1
            && client.getVarbitValue(VarbitID.FAIRYRING_1) == 1 && client.getVarbitValue(VarbitID.FAIRYRING_2) == 0
            && client.getVarbitValue(VarbitID.FAIRYRING_3) == 3;
        if (portal || (spell && "cast".equals(option)) || tablet || cape || ownDialog || fairyHouse)
        {
            ownHouseConfirmed = false;
            entryTick = client.getTickCount();
            entrySawLoading = false;
            outsideAfterLoadingTick = -1;
        }
        else if (entryTick >= 0 && (objectAction || action == MenuAction.WALK || action == MenuAction.WIDGET_CONTINUE))
        {
            // A failed teleport must not authorise a later, unrelated entrance.
            if (entrySawLoading) isOwnHouse(client);
            clearEntry();
        }
    }

    boolean tick(Client client, Instant at)
    {
        if (!isOwnHouse(client))
        {
            ownSince = -1;
            return false;
        }
        int now = client.getTickCount();
        if (ownSince < 0) ownSince = now;
        boolean read = nexus(client, at);
        if (scanned || now - ownSince < SETTLE_TICKS) return read;
        scanned = true;
        WorldView view = viewOf(client);
        if (view == null || view.getScene() == null) return read;
        Tile[][][] tiles = view.getScene().getTiles();
        JsonObject layout = layout(view.getInstanceTemplateChunks(), (plane, x, y) -> idsOn(tiles, plane, x, y),
            id -> { ObjectComposition object = client.getObjectDefinition(id); return object == null ? null : object.getName(); });
        if (layout == null) return read;
        layout.add("pets", pets(client));
        seen(layout, at);
        return true;
    }

    /** The nexus Teleport menu's row texts while it is open; an empty or unchanged menu is not a new read. */
    private boolean nexus(Client client, Instant at)
    {
        Widget list = client.getWidget(InterfaceID.TelenexusTeleport.TEXT1);
        if (list == null || list.isHidden())
        {
            nexusShown = null;
            return false;
        }
        JsonArray texts = nexusTexts(widgetTexts(list, 3));
        if (texts.size() == 0 || texts.equals(nexusShown)) return false;
        nexusShown = texts;
        seenNexus(texts, at);
        return true;
    }

    void seenNexus(JsonArray texts, Instant at)
    {
        pendingNexus = texts;
        pendingNexusAt = at;
    }

    private static List<String> widgetTexts(Widget widget, int depth)
    {
        List<String> texts = new ArrayList<>();
        texts.add(widget.getText());
        if (depth > 0)
            for (Widget[] group : new Widget[][] {widget.getDynamicChildren(), widget.getStaticChildren(), widget.getNestedChildren()})
                if (group != null) for (Widget child : group) if (child != null && !child.isHidden()) texts.addAll(widgetTexts(child, depth - 1));
        return texts;
    }

    /**
     * The menu's destinations: tags stripped, trimmed, empties and repeats dropped. Too many or too long: not a menu we
     * understand, so nothing.
     */
    static JsonArray nexusTexts(List<String> raw)
    {
        LinkedHashSet<String> texts = new LinkedHashSet<>();
        for (String text : raw)
        {
            String plain = text == null ? "" : Text.removeTags(text).replace(' ', ' ').trim();
            plain = NEXUS_KEY.matcher(plain).replaceFirst("");
            if (plain.length() > MAX_NEXUS_TEXT_LENGTH) return new JsonArray();
            if (!plain.isEmpty()) texts.add(plain);
        }
        JsonArray list = new JsonArray();
        if (texts.size() > MAX_NEXUS_TEXTS) return list;
        for (String text : texts) list.add(text);
        return list;
    }

    void seen(JsonObject layout, Instant at)
    {
        pending = layout;
        pendingAt = at;
    }

    interface Objects
    {
        int[] on(int plane, int x, int y);
    }

    static JsonObject layout(int[][][] chunks, Objects objects, IntFunction<String> names)
    {
        if (chunks == null) return null;
        JsonArray rooms = new JsonArray();
        JsonArray furniture = new JsonArray();
        for (int plane = 0; plane < chunks.length; plane++)
        {
            if (chunks[plane] == null) continue;
            for (int cx = 0; cx < chunks[plane].length; cx++)
            {
                if (chunks[plane][cx] == null) continue;
                for (int cy = 0; cy < chunks[plane][cx].length; cy++)
                {
                    int data = chunks[plane][cx][cy];
                    if (data == -1 || !isTemplate((data >> 14 & 0x3FF) * 8, (data >> 3 & 0x7FF) * 8)) continue;
                    rooms.add(cell(plane, cx, cy, data));
                    Map<Integer, Integer> copies = new TreeMap<>();
                    for (int x = cx * 8; x < cx * 8 + 8; x++)
                        for (int y = cy * 8; y < cy * 8 + 8; y++)
                            for (int id : objects.on(plane, x, y))
                            {
                                String name = names.apply(id);
                                if (name != null && !name.isEmpty() && !"null".equals(name)) copies.merge(id, 1, Integer::sum);
                            }
                    if (copies.isEmpty() || copies.size() > MAX_OBJECTS_PER_ROOM) continue;
                    JsonArray list = new JsonArray(), counts = new JsonArray();
                    for (Map.Entry<Integer, Integer> entry : copies.entrySet())
                    {
                        list.add(entry.getKey());
                        counts.add(entry.getValue());
                    }
                    JsonArray room = cell(plane, cx, cy, 0);
                    room.set(3, list);
                    room.add(counts);
                    furniture.add(room);
                }
            }
        }
        if (rooms.size() == 0) return null;
        JsonObject layout = new JsonObject();
        layout.add("rooms", rooms);
        layout.add("objects", furniture);
        return layout;
    }

    /** The upload's row: settings now, plus the layout and nexus menu from this visit or kept from the last row. */
    static Observation observation(int location, int servant, HouseCapture capture, Instant at)
    {
        JsonObject fresh = capture.pending;
        Instant freshAt = capture.pendingAt;
        JsonArray freshNexus = capture.pendingNexus;
        Instant freshNexusAt = capture.pendingNexusAt;
        capture.pending = null;
        capture.pendingAt = null;
        capture.pendingNexus = null;
        capture.pendingNexusAt = null;
        return Observation.merging(previous -> {
            JsonObject row = new JsonObject();
            row.addProperty("location", location);
            row.addProperty("servant", servant);
            JsonObject old = previous == null || previous.size() == 0 ? null : previous.get(0).getAsJsonObject();
            if (fresh != null)
            {
                row.addProperty("seenAt", ChangeAwareSourceCapture.time(freshAt));
                for (String key : new String[] {"rooms", "objects", "pets"}) row.add(key, fresh.get(key).deepCopy());
            }
            else
                for (String key : new String[] {"seenAt", "rooms", "objects", "pets"})
                    row.add(key, old != null && old.has(key) ? old.get(key).deepCopy() : JsonNull.INSTANCE);
            if (freshNexus != null)
            {
                row.addProperty("nexusSeenAt", ChangeAwareSourceCapture.time(freshNexusAt));
                row.add("nexus", freshNexus.deepCopy());
            }
            else
                for (String key : new String[] {"nexusSeenAt", "nexus"})
                    row.add(key, old != null && old.has(key) ? old.get(key).deepCopy() : JsonNull.INSTANCE);
            JsonArray value = new JsonArray();
            value.add(row);
            return value;
        }, ChangeAwareSourceCapture.time(at));
    }

    void reset()
    {
        ownHouseConfirmed = false;
        ownSince = -1;
        scanned = false;
        pending = null;
        pendingAt = null;
        nexusShown = null;
        pendingNexus = null;
        pendingNexusAt = null;
        clearEntry();
    }

    boolean isOwnHouse(Client client)
    {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) return false;
        int tick = client.getTickCount();
        if (entryTick >= 0 && (tick < entryTick || tick - entryTick > ENTRY_WINDOW_TICKS)) clearEntry();
        if (!isHouse(client))
        {
            ownHouseConfirmed = false;
            if (entrySawLoading)
            {
                // The way in can load the outside portal for a tick before the house rooms; a settled outside arrival ends it.
                if (outsideAfterLoadingTick < 0) outsideAfterLoadingTick = tick;
                else if (tick != outsideAfterLoadingTick) clearEntry();
            }
            return false;
        }
        if (client.getVarbitValue(VarbitID.POH_BUILDING_MODE) == 1 || (entryTick >= 0 && entrySawLoading)) ownHouseConfirmed = true;
        return ownHouseConfirmed;
    }

    private void clearEntry()
    {
        entryTick = -1;
        entrySawLoading = false;
        outsideAfterLoadingTick = -1;
    }

    private static WorldView viewOf(Client client)
    {
        Player player = client.getLocalPlayer();
        LocalPoint local = player == null ? null : player.getLocalLocation();
        return local == null ? null : client.getWorldView(local.getWorldView());
    }

    private static boolean isHouse(Client client)
    {
        Player player = client.getLocalPlayer();
        LocalPoint local = player == null ? null : player.getLocalLocation();
        WorldView view = viewOf(client);
        if (local == null || view == null || !view.isInstance()) return false;
        int[][][] chunks = view.getInstanceTemplateChunks();
        int plane = view.getPlane(), x = local.getSceneX() / 8, y = local.getSceneY() / 8;
        if (chunks == null || plane < 0 || plane >= chunks.length || chunks[plane] == null || x < 0 || x >= chunks[plane].length
            || chunks[plane][x] == null || y < 0 || y >= chunks[plane][x].length || chunks[plane][x][y] == -1) return false;
        return HOUSE_REGIONS.contains(WorldPoint.fromLocalInstance(client, local).getRegionID());
    }

    private static int[] idsOn(Tile[][][] tiles, int plane, int x, int y)
    {
        if (tiles == null || plane >= tiles.length || tiles[plane] == null || x >= tiles[plane].length || tiles[plane][x] == null
            || y >= tiles[plane][x].length || tiles[plane][x][y] == null) return new int[0];
        return idsOn(tiles[plane][x][y], x, y);
    }

    /** A multi-tile game object belongs to its south-west tile; walls and decorations take one tile each. */
    static int[] idsOn(Tile tile, int x, int y)
    {
        List<Integer> ids = new ArrayList<>();
        GameObject[] games = tile.getGameObjects();
        if (games != null)
            for (GameObject game : games)
            {
                if (game == null) continue;
                Point corner = game.getSceneMinLocation();
                if (corner == null || (corner.getX() == x && corner.getY() == y)) ids.add(game.getId());
            }
        WallObject wall = tile.getWallObject();
        if (wall != null) ids.add(wall.getId());
        DecorativeObject decorative = tile.getDecorativeObject();
        if (decorative != null) ids.add(decorative.getId());
        GroundObject ground = tile.getGroundObject();
        if (ground != null) ids.add(ground.getId());
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    static JsonElement pets(Client client)
    {
        ItemContainer pets = client.getItemContainer(MENAGERIE_CONTAINER + 0x8000);
        if (pets == null) pets = client.getItemContainer(MENAGERIE_CONTAINER);
        EnumComposition petEnum = client.getEnum(MENAGERIE_PET_ENUM);
        // No container means no item pets (the game sends none for a menagerie without them); the bit pets are read either way.
        if (pets == null && petEnum == null) return JsonNull.INSTANCE;
        List<Integer> items = new ArrayList<>();
        if (pets != null)
            for (Item item : pets.getItems()) if (item != null && item.getId() > 0 && item.getQuantity() > 0) items.add(item.getId());
        return pets(items, new int[] {client.getVarpValue(VarPlayerID.PRAYER20), client.getVarpValue(VarPlayerID.MENAGERIE_CONTENTS2),
            client.getVarpValue(VarPlayerID.MENAGERIE_CONTENTS3)}, petEnum == null ? key -> -1 : petEnum::getIntValue);
    }

    /**
     * Item pets from the container, then bit pets from three varps (PRAYER20, MENAGERIE_CONTENTS2 and 3), each set bit a key of
     * enum 985, looked up by key, never position. Bit layout from Dude Where's My Stuff (Menagerie.java, BSD-2): keys 0-31 in the
     * first varp, 32-62 and 63-93 in bits 0-30 of the others.
     */
    static JsonArray pets(List<Integer> items, int[] bits, IntUnaryOperator petAt)
    {
        JsonArray ids = new JsonArray();
        for (int id : items) ids.add(id);
        for (int key = 0; key < 94; key++)
        {
            int word = key < 32 ? 0 : key < 63 ? 1 : 2;
            int bit = key < 32 ? key : key < 63 ? key - 32 : key - 63;
            int pet = (bits[word] & (1 << bit)) != 0 ? petAt.applyAsInt(key) : -1;
            if (pet > 0) ids.add(pet);
        }
        return ids;
    }

    private static JsonArray cell(int plane, int cx, int cy, int value)
    {
        JsonArray cell = new JsonArray();
        cell.add(plane);
        cell.add(cx);
        cell.add(cy);
        cell.add(value);
        return cell;
    }

    private static boolean isObjectAction(MenuAction action)
    {
        return action == MenuAction.GAME_OBJECT_FIRST_OPTION || action == MenuAction.GAME_OBJECT_SECOND_OPTION
            || action == MenuAction.GAME_OBJECT_THIRD_OPTION || action == MenuAction.GAME_OBJECT_FOURTH_OPTION
            || action == MenuAction.GAME_OBJECT_FIFTH_OPTION;
    }

    private static boolean isWidgetAction(MenuAction action)
    {
        return action == MenuAction.CC_OP || action == MenuAction.CC_OP_LOW_PRIORITY || action == MenuAction.WIDGET_TYPE_1;
    }

    private static boolean isItemAction(MenuAction action)
    {
        return action == MenuAction.ITEM_FIRST_OPTION || action == MenuAction.ITEM_SECOND_OPTION
            || action == MenuAction.ITEM_THIRD_OPTION || action == MenuAction.ITEM_FOURTH_OPTION
            || action == MenuAction.ITEM_FIFTH_OPTION;
    }

    private static String plain(String text)
    {
        return text == null ? "" : Text.removeTags(text).trim().toLowerCase(Locale.ROOT);
    }
}
