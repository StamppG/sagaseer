package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;

/**
 * The state.music rows: the cache's track list and which tracks are unlocked (bit `bit` of MUSICMULTI_n). The list is read once
 * per session; each snapshot reads only the variables. Hidden tracks are left out.
 */
final class GameMusic
{
    private static final int MAX_ROWS = 1200;
    private static final int MAX_TEXT = 200;

    /** One track as the cache lists it. */
    private static final class Track
    {
        final int row;
        final String name;
        final String hint;
        final int varp;
        final int bit;

        Track(int row, String name, String hint, int varp, int bit)
        {
            this.row = row;
            this.name = name;
            this.hint = hint;
            this.varp = varp;
            this.bit = bit;
        }
    }

    /** Entry n-1 is MUSICMULTI_n. */
    private static final int[] MUSIC_VARPS = {
        VarPlayerID.MUSICMULTI_1, VarPlayerID.MUSICMULTI_2, VarPlayerID.MUSICMULTI_3, VarPlayerID.MUSICMULTI_4,
        VarPlayerID.MUSICMULTI_5, VarPlayerID.MUSICMULTI_6, VarPlayerID.MUSICMULTI_7, VarPlayerID.MUSICMULTI_8,
        VarPlayerID.MUSICMULTI_9, VarPlayerID.MUSICMULTI_10, VarPlayerID.MUSICMULTI_11, VarPlayerID.MUSICMULTI_12,
        VarPlayerID.MUSICMULTI_13, VarPlayerID.MUSICMULTI_14, VarPlayerID.MUSICMULTI_15, VarPlayerID.MUSICMULTI_16,
        VarPlayerID.MUSICMULTI_17, VarPlayerID.MUSICMULTI_18, VarPlayerID.MUSICMULTI_19, VarPlayerID.MUSICMULTI_20,
        VarPlayerID.MUSICMULTI_21, VarPlayerID.MUSICMULTI_22, VarPlayerID.MUSICMULTI_23, VarPlayerID.MUSICMULTI_24,
        VarPlayerID.MUSICMULTI_25, VarPlayerID.MUSICMULTI_26, VarPlayerID.MUSICMULTI_27};

    private List<Track> tracks;
    private final Consumer<String> debug;
    private String lastUnavailable;

    GameMusic() { this(why -> { }); }

    /** `debug` gets a line when music becomes unavailable (again only if the reason changes) and when the list is read. */
    GameMusic(Consumer<String> debug) { this.debug = debug; }

    /** Client thread. Nothing when not logged in. */
    Observation read(Client client, Supplier<Instant> clock)
    {
        if (client == null || client.getLocalPlayer() == null || clock == null)
            return Observation.notRead();
        return read(client::getDBTableRows, client::getDBTableField, client::getVarpValue, clock);
    }

    Observation read(IntFunction<List<Integer>> tableRows, GameActivities.Field field, IntUnaryOperator varp,
        Supplier<Instant> clock)
    {
        try
        {
            if (tracks == null)
            {
                List<Integer> tableRowIds = tableRows.apply(DBTableID.Music.ID);
                if (tableRowIds == null || tableRowIds.isEmpty()) return unavailable("table " + DBTableID.Music.ID + " has no rows");
                tracks = list(tableRowIds, field, debug);
            }
            JsonArray array = new JsonArray();
            for (Track track : tracks)
            {
                JsonObject row = new JsonObject();
                row.addProperty("track", track.row);
                row.addProperty("name", track.name);
                row.addProperty("hint", track.hint);
                row.addProperty("unlocked", bitSet(varp.applyAsInt(track.varp), track.bit));
                array.add(row);
            }
            lastUnavailable = null;
            return new Observation(array, ChangeAwareSourceCapture.time(clock.get()));
        }
        catch (RuntimeException failed)
        {
            return unavailable(failed.getClass().getSimpleName() + ": " + failed.getMessage());
        }
    }

    private Observation unavailable(String why)
    {
        if (!why.equals(lastUnavailable)) debug.accept("Music unavailable: " + why);
        lastUnavailable = why;
        return Observation.unavailable();
    }

    /** Bit `bit` (0 to 31) of a player variable. */
    static boolean bitSet(int value, int bit)
    {
        return (value >>> bit & 1) != 0;
    }

    /**
     * One component of a column's tuple, or -1. getDBTableField takes the position within the tuple, so a two-part column needs
     * one call per part.
     */
    private static int component(GameActivities.Field field, int row, int column, int position)
    {
        Object[] values = field.get(row, column, position);
        return values != null && values.length > 0 && values[0] instanceof Number ? ((Number) values[0]).intValue() : -1;
    }

    private static List<Track> list(List<Integer> tableRowIds, GameActivities.Field field, Consumer<String> debug)
    {
        List<Track> list = new ArrayList<>();
        int hidden = 0;
        int invalid = 0;
        for (int row : tableRowIds)
        {
            int n = component(field, row, DBTableID.Music.COL_VARIABLE, 0);
            int bit = component(field, row, DBTableID.Music.COL_VARIABLE, 1);
            if (n < 1 && bit < 0)
            {
                hidden++; // no variable: the game's default (-1, -1)
                continue;
            }
            Object[] names = field.get(row, DBTableID.Music.COL_DISPLAYNAME, 0);
            Object[] hints = field.get(row, DBTableID.Music.COL_UNLOCKHINT, 0);
            Object name = names != null && names.length > 0 ? names[0] : null;
            Object hint = hints != null && hints.length > 0 ? hints[0] : "";
            if (n < 1 || n > MUSIC_VARPS.length || bit < 0 || bit > 31 || row < 1 || !(name instanceof String) || !(hint instanceof String)
                || ((String) name).isEmpty() || ((String) name).length() > MAX_TEXT || ((String) hint).length() > MAX_TEXT)
            {
                invalid++;
                continue;
            }
            list.add(new Track(row, (String) name, (String) hint, MUSIC_VARPS[n - 1], bit));
        }
        debug.accept("Music: " + list.size() + " tracks listed from " + tableRowIds.size() + " rows (" + hidden + " without a variable, " + invalid + " unusable)");
        if (list.isEmpty() || list.size() > MAX_ROWS) throw new IllegalStateException("music tracks: " + list.size());
        return list;
    }
}
