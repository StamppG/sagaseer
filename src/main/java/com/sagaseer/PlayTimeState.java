package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;

/**
 * The state.timePlayed row: the game's time played (varclient 526), sent only while the player shows it in the Account Summary,
 * since hiding it is their choice. Kept until the session ends.
 */
final class PlayTimeState
{
    private static final int MINUTES_PER_HOUR = 60;

    private Integer minutes;
    private Instant observedAt;

    void reset()
    {
        minutes = null;
        observedAt = null;
    }

    /** Reads the value, keeps it while shown, and gives the row. */
    Observation read(IntUnaryOperator varc, IntUnaryOperator varbit, Supplier<Instant> clock)
    {
        if (varc == null || varbit == null || clock == null) return Observation.notRead();
        try
        {
            // Hidden: drop what was held, before reading the time.
            if (varbit.applyAsInt(VarbitID.ACCOUNT_SUMMARY_DISPLAY_PLAYTIME) != 1)
            {
                reset();
                return observation();
            }
            // 0 is the value before the server has set it.
            int played = varc.applyAsInt(VarClientID.ACCOUNT_SUMMARY_PLAYTIME);
            if (played > 0)
            {
                // Whole hours: every change is a History row, and minutes change all session.
                minutes = played - played % MINUTES_PER_HOUR;
                observedAt = clock.get();
            }
        }
        catch (RuntimeException unreadable) { /* keep what was read earlier */ }
        return observation();
    }

    Observation observation()
    {
        if (minutes == null) return Observation.notRead();
        JsonObject row = new JsonObject();
        row.addProperty("minutes", minutes);
        JsonArray rows = new JsonArray();
        rows.add(row);
        return new Observation(rows, ChangeAwareSourceCapture.time(observedAt));
    }
}
