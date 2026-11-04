package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The state.hunterRumours row from what the game says this session: the guild total ("You have completed N rumours..."), the
 * current rumour, and each hunter's held one (from Guild Hunter dialogue). Each part merges onto the last uploaded row. A hand-in
 * clears the current rumour. A switch ("Unless you fancy hunting for me instead?", answered yes) makes that hunter's rumour
 * current and leaves the old one held by its hunter.
 */
final class HunterRumourState
{
    private static final Pattern REPORTED_TOTAL = Pattern.compile(
            "You have completed (?:@[a-zA-Z0-9]+@|<col=[0-9a-fA-F]{6}>)?" +
            "([0-9]+|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:</col>)?" +
            " rumours? for the Hunter Guild\\.");

    static final class Total
    {
        final int reportedTotal;
        final Instant observedAt;

        Total(int reportedTotal, Instant observedAt)
        {
            this.reportedTotal = reportedTotal;
            this.observedAt = observedAt;
        }
    }

    private Total total;
    /** The rumour, valid when currentSeenAt is set; null with a time means handed in. */
    private HunterRumourDialogue.Rumour current;
    private Instant currentSeenAt;
    /** Each hunter's held rumour this session, or null once they hold none. */
    private final Map<String, Held> held = new LinkedHashMap<>();
    /**
     * The hunter who just offered a switch: their very next line within OFFER_SECONDS is the switched rumour. Any other hunter
     * line ends the offer. Real switches took 11-12 seconds (the yes/no menu), so the limit is a minute.
     */
    private String offeredBy;
    private HunterRumourDialogue.OfferKind offeredKind;
    private Instant offeredAt;
    private Instant clearedAt;
    private static final long OFFER_SECONDS = 60;
    /** A switch before this session saw the current rumour: the last uploaded current moves to its hunter's held entry. */
    private boolean switchedFromUnseen;

    private static final class Held
    {
        final HunterRumourDialogue.Rumour rumour;
        final Instant at;

        Held(HunterRumourDialogue.Rumour rumour, Instant at)
        {
            this.rumour = rumour;
            this.at = at;
        }
    }

    void reset()
    {
        total = null;
        current = null;
        currentSeenAt = null;
        held.clear();
        offeredBy = null;
        offeredKind = null;
        offeredAt = null;
        clearedAt = null;
        switchedFromUnseen = false;
    }

    /** True for the guild total line, newer than the one held. A hand-in clears the rumour. */
    boolean observeTotal(String message, Instant at)
    {
        if (message == null || at == null) return false;
        Matcher match = REPORTED_TOTAL.matcher(message);
        if (!match.matches()) return false;
        final long parsed;
        try { parsed = Long.parseLong(match.group(1).replace(",", "")); }
        catch (NumberFormatException invalid) { return false; }
        if (parsed > Integer.MAX_VALUE || (total != null && at.isBefore(total.observedAt))) return false;
        total = new Total((int) parsed, at);
        if (currentSeenAt == null || !at.isBefore(currentSeenAt))
        {
            current = null;
            currentSeenAt = at;
        }
        return true;
    }

    /** True when a Guild Hunter's line names a current or held rumour newer than known. */
    boolean observeDialogue(String speaker, String text, Instant at)
    {
        if (at == null) return false;
        String offer = HunterRumourDialogue.offeredBy(speaker, text);
        if (offer != null)
        {
            offeredBy = offer;
            offeredKind = HunterRumourDialogue.offerKind(text);
            offeredAt = at;
            // Gilman's offer also recalls the one he holds; keep that evidence.
            HunterRumourDialogue.Rumour recalled = HunterRumourDialogue.parse(speaker, text);
            if (recalled == null || !recalled.held) return false;
            held.put(recalled.hunter, new Held(recalled, at));
            return true;
        }
        String speaking = HunterRumourDialogue.hunterOf(speaker);
        boolean armed = speaking != null && speaking.equals(offeredBy) && offeredAt != null
            && !at.isBefore(offeredAt) && !at.isAfter(offeredAt.plusSeconds(OFFER_SECONDS));
        HunterRumourDialogue.OfferKind kind = offeredKind;
        if (speaking != null)
        {
            offeredBy = null;
            offeredKind = null;
            offeredAt = null;
        }
        HunterRumourDialogue.Rumour rumour = HunterRumourDialogue.parse(speaker, text);
        if (rumour == null) return false;
        boolean assigned = text != null && text.contains("been rumours of");
        boolean fresh = armed && kind == HunterRumourDialogue.OfferKind.FRESH && assigned;
        if (fresh)
        {
            if (currentSeenAt != null && at.isBefore(currentSeenAt)) return false;
            // Brand new clears every held rumour.
            held.clear();
            clearedAt = at;
            current = rumour;
            currentSeenAt = at;
            switchedFromUnseen = false;
            return true;
        }
        boolean switched = armed && rumour.hunter.equals(speaking) && (rumour.held || assigned);
        if (switched)
        {
            if (currentSeenAt != null && at.isBefore(currentSeenAt)) return false;
            HunterRumourDialogue.Rumour left = current;
            if (currentSeenAt == null) switchedFromUnseen = true;
            current = new HunterRumourDialogue.Rumour(rumour.hunter, rumour.tier, rumour.creature);
            currentSeenAt = at;
            held.put(rumour.hunter, new Held(null, at));
            if (left != null && !left.hunter.equals(rumour.hunter)) held.put(left.hunter, new Held(left, at));
            return true;
        }
        if (rumour.held)
        {
            Held known = held.get(rumour.hunter);
            if (known != null && at.isBefore(known.at)) return false;
            held.put(rumour.hunter, new Held(rumour, at));
            return true;
        }
        if (currentSeenAt != null && at.isBefore(currentSeenAt)) return false;
        current = rumour;
        currentSeenAt = at;
        // A rumour that is current is not also held by its hunter.
        Held known = held.get(rumour.hunter);
        if (known == null || !at.isBefore(known.at)) held.put(rumour.hunter, new Held(null, at));
        return true;
    }

    Total snapshot() { return total; }

    HunterRumourDialogue.Rumour currentRumour() { return current; }

    /** The section merged onto the last row, or nothing before the game has said anything. */
    Observation observation()
    {
        if (total == null && currentSeenAt == null && held.isEmpty()) return Observation.notRead();
        final Total seenTotal = total;
        final HunterRumourDialogue.Rumour rumour = current;
        final boolean rumourKnown = currentSeenAt != null;
        final Map<String, Held> heldNow = new LinkedHashMap<>(held);
        final boolean leftUnseen = switchedFromUnseen;
        final Instant clear = clearedAt;
        Instant newest = null;
        if (seenTotal != null) newest = seenTotal.observedAt;
        if (currentSeenAt != null && (newest == null || currentSeenAt.isAfter(newest))) newest = currentSeenAt;
        for (Held entry : heldNow.values())
            if (newest == null || entry.at.isAfter(newest)) newest = entry.at;
        return Observation.merging(previous -> merge(previous, seenTotal, rumour, rumourKnown, heldNow, leftUnseen, clear),
            ChangeAwareSourceCapture.time(newest));
    }

    private static JsonObject rumourJson(HunterRumourDialogue.Rumour rumour)
    {
        JsonObject json = new JsonObject();
        json.addProperty("hunter", rumour.hunter);
        json.addProperty("tier", rumour.tier);
        json.addProperty("creature", rumour.creature);
        return json;
    }

    private static JsonArray merge(JsonArray previous, Total total, HunterRumourDialogue.Rumour rumour, boolean rumourKnown,
        Map<String, Held> heldNow, boolean leftUnseen, Instant clear)
    {
        JsonObject old = previous != null && previous.size() == 1 && previous.get(0).isJsonObject()
            ? previous.get(0).getAsJsonObject() : new JsonObject();
        JsonObject row = new JsonObject();
        JsonElement completed = old.has("completed") ? old.get("completed") : JsonNull.INSTANCE;
        if (total != null) row.addProperty("completed", total.reportedTotal); else row.add("completed", completed);
        if (rumourKnown)
        {
            if (rumour == null) row.add("current", JsonNull.INSTANCE);
            else row.add("current", rumourJson(rumour));
        }
        else row.add("current", old.has("current") ? old.get("current") : JsonNull.INSTANCE);
        // Held rumours: the last upload's, with each hunter heard this session replaced or dropped; the key appears only once
        // there is something to say.
        Map<String, JsonObject> keptHeld = new LinkedHashMap<>();
        if (clear == null && old.has("held") && old.get("held").isJsonArray())
            for (JsonElement entry : old.getAsJsonArray("held"))
                keptHeld.put(entry.getAsJsonObject().get("hunter").getAsString(), entry.getAsJsonObject());
        boolean hadHeldKey = old.has("held");
        // The last upload's current goes to its hunter after an unseen-current switch.
        if (leftUnseen && rumour != null && old.has("current") && old.get("current").isJsonObject())
        {
            JsonObject left = old.getAsJsonObject("current");
            String hunter = left.has("hunter") ? left.get("hunter").getAsString() : null;
            if (hunter != null && !hunter.equals(rumour.hunter)) keptHeld.put(hunter, left);
        }
        for (Map.Entry<String, Held> entry : heldNow.entrySet())
        {
            if (entry.getValue().rumour == null) keptHeld.remove(entry.getKey());
            else keptHeld.put(entry.getKey(), rumourJson(entry.getValue().rumour));
        }
        if (!keptHeld.isEmpty() || hadHeldKey || clear != null)
        {
            JsonArray heldRows = new JsonArray();
            for (JsonObject entry : keptHeld.values()) heldRows.add(entry);
            row.add("held", heldRows);
        }
        JsonArray rows = new JsonArray();
        rows.add(row);
        return rows;
    }
}
