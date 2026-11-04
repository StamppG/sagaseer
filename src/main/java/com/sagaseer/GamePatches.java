package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.VarPlayerID;

/**
 * The state.farming row: the latest sighting of nearby patches. Patch values share varbits, so they are read only where
 * FarmingRegions says which patches they are, and count once the same on two consecutive ticks. An area uploads when its values
 * changed, or on a revisit after REVISIT_GAP; standing still sends nothing. Rows belong to one character name.
 */
final class GamePatches
{
    /** One tick's values: every farming area at the tile, and the Fossil Island bird house varps. */
    private static final class Reading
    {
        final Map<Integer, int[]> regions = new LinkedHashMap<>();
        int[] birdHouses;
        int[] compost;

        String key()
        {
            StringBuilder key = new StringBuilder();
            for (Map.Entry<Integer, int[]> area : regions.entrySet())
                key.append(area.getKey()).append(Arrays.toString(area.getValue()));
            key.append(compost == null ? "-" : Arrays.toString(compost));
            return key.append(birdHouses == null ? "-" : Arrays.toString(birdHouses)).toString();
        }
    }

    /** The least time between two sightings of an area whose values have not changed. */
    static final Duration REVISIT_GAP = Duration.ofMinutes(5);

    private String account;
    private String pending;
    private final Map<Integer, int[]> sent = new HashMap<>();
    private int[] sentBirdHouses;
    private int[] sentCompost;
    private final Map<Integer, Instant> sentAt = new HashMap<>();
    private Instant sentBirdsAt;
    /** The areas of the last counted reading: entering one is a visit. */
    private final Set<Integer> inside = new HashSet<>();
    private boolean insideBirds;
    private JsonObject latest;
    private Instant latestAt;
    /** Whether the latest sighting was handed to an upload; until then new sightings add to it. */
    private boolean taken = true;

    /** A logout, hop or lost connection (never a loading screen). */
    void reset()
    {
        account = null;
        pending = null;
        sent.clear();
        sentBirdHouses = null;
        sentCompost = null;
        sentAt.clear();
        sentBirdsAt = null;
        inside.clear();
        insideBirds = false;
        latest = null;
        latestAt = null;
        taken = true;
    }

    /** Once per tick (client thread); true when a new sighting should upload. (map, x, y, plane) is the world tile. */
    boolean tick(String character, int map, int x, int y, int plane, IntUnaryOperator varbit, IntUnaryOperator varp, Instant now)
    {
        if (character == null || character.isBlank() || now == null) return false;
        if (!character.equals(account))
        {
            reset();
            account = character;
        }
        List<FarmingRegions.Region> areas = FarmingRegions.at(map, x, y, plane);
        boolean birdHouses = FarmingRegions.birdHouseArea(map);
        if (areas.isEmpty() && !birdHouses)
        {
            pending = null;
            inside.clear();
            insideBirds = false;
            return false;
        }
        Reading reading = new Reading();
        for (FarmingRegions.Region area : areas)
        {
            int[] values = new int[area.varbits.length];
            for (int i = 0; i < values.length; i++) values[i] = varbit.applyAsInt(area.varbits[i]);
            reading.regions.put(area.id, values);
        }
        if (!areas.isEmpty())
        {
            // Six signed transmit varps, each packing sixteen two-bit compost values.
            reading.compost = new int[6];
            for (int i = 0; i < reading.compost.length; i++) reading.compost[i] = varp.applyAsInt(VarPlayerID.FARMING_COMPOST_TRANSMIT_1 + i);
        }
        if (birdHouses)
        {
            reading.birdHouses = new int[FarmingRegions.BIRD_HOUSE_VARPS.length];
            for (int i = 0; i < reading.birdHouses.length; i++) reading.birdHouses[i] = varp.applyAsInt(FarmingRegions.BIRD_HOUSE_VARPS[i]);
        }
        String key = reading.key();
        if (!key.equals(pending))
        {
            pending = key;
            return false;
        }
        // Sent again: changed values, or an area entered again after the revisit gap.
        List<Integer> changed = new ArrayList<>();
        for (Map.Entry<Integer, int[]> area : reading.regions.entrySet())
            if (!Arrays.equals(sent.get(area.getKey()), area.getValue()) || !Arrays.equals(sentCompost, reading.compost)
                || (!inside.contains(area.getKey()) && revisitDue(sentAt.get(area.getKey()), now)))
                changed.add(area.getKey());
        boolean birdsChanged = reading.birdHouses != null
            && (!Arrays.equals(sentBirdHouses, reading.birdHouses) || (!insideBirds && revisitDue(sentBirdsAt, now)));
        if (changed.isEmpty() && !birdsChanged)
        {
            enter(reading);
            return false;
        }
        if (latestAt != null && !now.isAfter(latestAt)) return false;
        enter(reading);

        JsonObject row = new JsonObject();
        row.addProperty("observedAt", ChangeAwareSourceCapture.time(now));
        JsonArray regions = new JsonArray();
        for (int id : changed)
        {
            FarmingRegions.Region area = region(id);
            int[] values = reading.regions.get(id);
            JsonObject entry = new JsonObject();
            entry.addProperty("region", id);
            JsonArray list = new JsonArray();
            for (int i = 0; i < values.length; i++)
            {
                JsonObject value = new JsonObject();
                value.addProperty("varbit", area.varbits[i]);
                value.addProperty("value", values[i]);
                list.add(value);
            }
            entry.add("values", list);
            regions.add(entry);
            sent.put(id, values);
            sentAt.put(id, now);
        }
        row.add("regions", regions);
        if (reading.compost != null)
        {
            JsonArray compost = new JsonArray();
            for (int value : reading.compost) compost.add(value);
            row.add("compost", compost);
            sentCompost = reading.compost;
        }
        if (birdsChanged)
        {
            JsonArray list = new JsonArray();
            for (int i = 0; i < reading.birdHouses.length; i++)
            {
                JsonObject value = new JsonObject();
                value.addProperty("varp", FarmingRegions.BIRD_HOUSE_VARPS[i]);
                value.addProperty("value", reading.birdHouses[i]);
                list.add(value);
            }
            row.add("birdHouses", list);
            sentBirdHouses = reading.birdHouses;
            sentBirdsAt = now;
        }
        else row.add("birdHouses", JsonNull.INSTANCE);
        if (latest != null && !taken)
        {
            // Not uploaded yet: keep the earlier areas, so crossing two areas loses neither.
            for (JsonElement old : latest.getAsJsonArray("regions"))
                if (!changed.contains(old.getAsJsonObject().get("region").getAsInt())) regions.add(old);
            if (!birdsChanged && !latest.get("birdHouses").isJsonNull()) row.add("birdHouses", latest.get("birdHouses"));
        }
        latest = row;
        latestAt = now;
        taken = false;
        return true;
    }

    private static boolean revisitDue(Instant sentAt, Instant now)
    {
        return sentAt == null || !now.isBefore(sentAt.plus(REVISIT_GAP));
    }

    private void enter(Reading reading)
    {
        inside.clear();
        inside.addAll(reading.regions.keySet());
        insideBirds = reading.birdHouses != null;
    }

    private static FarmingRegions.Region region(int id)
    {
        for (FarmingRegions.Region region : FarmingRegions.ALL) if (region.id == id) return region;
        throw new IllegalStateException("unknown farming region " + id);
    }

    /** The section value, or nothing before a patch has been seen this session. */
    Observation observation()
    {
        if (latest == null) return Observation.notRead();
        taken = true;
        JsonArray rows = new JsonArray();
        rows.add(latest.deepCopy());
        return new Observation(rows, ChangeAwareSourceCapture.time(latestAt));
    }
}
