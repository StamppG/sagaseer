package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.util.Text;

/**
 * The state.stashBoard rows: Watson's noticeboard, the only place the game says which STASH units are filled. Each row is the
 * unit's place text, then one graphic per check mark (two marks mean filled, as Emote Clue Items reads it, BSD-2). The Backend
 * maps the words to units. Read each tick while open; only a read with all six tiers counts.
 */
final class StashBoard
{
    private static final String[] TIERS = { "BEGINNER", "EASY", "MEDIUM", "HARD", "ELITE", "MASTER" };
    private static final int[] LISTS = { InterfaceID.HideyHoles.BEGINNER_CONTENTS, InterfaceID.HideyHoles.EASY_CONTENTS,
        InterfaceID.HideyHoles.MEDIUM_CONTENTS, InterfaceID.HideyHoles.HARD_CONTENTS, InterfaceID.HideyHoles.ELITE_CONTENTS,
        InterfaceID.HideyHoles.MASTER_CONTENTS };

    private boolean open;
    private JsonArray complete;
    private String completeAt;
    private String completeAccount;

    void opened()
    {
        open = true;
    }

    void closed()
    {
        open = false;
    }

    boolean open()
    {
        return open;
    }

    /** One tick; true when this read differs from the last complete one. */
    boolean tick(String account, IntFunction<Widget[]> children, Supplier<Instant> clock)
    {
        if (!open || account == null || account.isBlank()) return false;
        JsonArray rows = new JsonArray();
        for (int tier = 0; tier < TIERS.length; tier++)
        {
            int before = rows.size();
            addRows(rows, TIERS[tier], children.apply(LISTS[tier]));
            if (rows.size() == before) return false;
        }
        if (account.equals(completeAccount) && rows.equals(complete)) return false;
        complete = rows;
        completeAccount = account;
        completeAt = ChangeAwareSourceCapture.time(clock.get());
        return true;
    }

    private static void addRows(JsonArray rows, String tier, Widget[] children)
    {
        if (children == null) return;
        JsonObject row = null;
        int marks = 0;
        for (Widget child : children)
        {
            if (child == null) continue;
            if (child.getType() == WidgetType.GRAPHIC && row != null) row.addProperty("marks", ++marks);
            if (child.getType() != WidgetType.TEXT) continue;
            String place = child.getText() == null ? "" : Text.removeTags(child.getText()).trim();
            row = null;
            marks = 0;
            if (place.isEmpty()) continue;
            row = new JsonObject();
            row.addProperty("tier", tier);
            row.addProperty("place", place);
            row.addProperty("marks", 0);
            rows.add(row);
        }
    }

    Observation observation(String account)
    {
        if (complete == null || account == null || !account.equals(completeAccount)) return Observation.notRead();
        return new Observation(complete.deepCopy(), completeAt);
    }

    void reset()
    {
        open = false;
        complete = null;
        completeAt = null;
        completeAccount = null;
    }
}
