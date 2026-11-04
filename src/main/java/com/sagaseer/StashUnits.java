package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.runelite.api.gameval.ObjectID;

/**
 * The state.stash rows: whether each STASH unit is built, from the game's own check script (WATSON_STASH_UNIT_CHECK), as
 * RuneLite's clue scroll plugin reads them (STASHUnit, BSD-2). It works anywhere with no interface open. Filled isn't knowable
 * here: only Watson's noticeboard shows it (StashBoard). A sync runs at login and on bank open, BATCH units a tick; it ends
 * without a result on a character change or an unexpected answer.
 */
final class StashUnits
{
    /** Units checked per tick. */
    static final int BATCH = 8;

    /** The units' object ids, in RuneLite's STASHUnit order. */
    static final int[] OBJECTS = {
        ObjectID.HH_EASY001, ObjectID.HH_EASY002, ObjectID.HH_EASY003, ObjectID.HH_EASY004,
        ObjectID.HH_EASY005, ObjectID.HH_EASY006, ObjectID.HH_EASY007, ObjectID.HH_EASY008,
        ObjectID.HH_EASY009, ObjectID.HH_EASY010, ObjectID.HH_EASY011, ObjectID.HH_EASY012,
        ObjectID.HH_EASY013, ObjectID.HH_EASY014, ObjectID.HH_EASY015, ObjectID.HH_EASY016,
        ObjectID.HH_EASY018, ObjectID.HH_EASY019, ObjectID.HH_EASY020, ObjectID.HH_EASY021,
        ObjectID.HH_EASY022, ObjectID.HH_EASY024, ObjectID.HH_EASY025, ObjectID.HH_EASY026,
        ObjectID.HH_EASY027, ObjectID.HH_EASY_EXP1, ObjectID.HH_EASY_EXP2, ObjectID.HH_EASY_EXP3,
        ObjectID.HH_EASY_EXP4, ObjectID.HH_MEDIUM001, ObjectID.HH_MEDIUM002, ObjectID.HH_MEDIUM003,
        ObjectID.HH_MEDIUM004, ObjectID.HH_MEDIUM005, ObjectID.HH_MEDIUM006, ObjectID.HH_MEDIUM007,
        ObjectID.HH_MEDIUM008, ObjectID.HH_MEDIUM009, ObjectID.HH_MEDIUM010, ObjectID.HH_MEDIUM011,
        ObjectID.HH_MEDIUM012, ObjectID.HH_MEDIUM013, ObjectID.HH_MEDIUM_EXP1, ObjectID.HH_MEDIUM_EXP2,
        ObjectID.HH_MEDIUM_EXP3, ObjectID.HH_MEDIUM_EXP4, ObjectID.HH_MEDIUM_EXP5, ObjectID.HH_MEDIUM_EXP6,
        ObjectID.HH_MEDIUM_EXP7, ObjectID.HH_MEDIUM_EXP8, ObjectID.HH_MEDIUM_EXP9, ObjectID.HH_HARD001,
        ObjectID.HH_HARD002, ObjectID.HH_HARD003, ObjectID.HH_HARD005, ObjectID.HH_HARD006,
        ObjectID.HH_HARD007, ObjectID.HH_HARD008, ObjectID.HH_HARD009, ObjectID.HH_HARD010,
        ObjectID.HH_HARD_EXP1, ObjectID.HH_HARD_EXP2, ObjectID.HH_HARD_EXP3, ObjectID.HH_HARD_EXP4,
        ObjectID.HH_HARD_EXP5, ObjectID.HH_HARD_EXP6, ObjectID.HH_ELITE_EXP1, ObjectID.HH_ELITE_EXP2,
        ObjectID.HH_ELITE_EXP3, ObjectID.HH_ELITE_EXP4, ObjectID.HH_ELITE_EXP5, ObjectID.HH_ELITE_EXP6,
        ObjectID.HH_ELITE_EXP7, ObjectID.HH_ELITE_EXP8, ObjectID.HH_ELITE_EXP9, ObjectID.HH_ELITE_EXP10,
        ObjectID.HH_ELITE_EXP11, ObjectID.HH_ELITE_EXP12, ObjectID.HH_ELITE_EXP13, ObjectID.HH_ELITE_EXP14,
        ObjectID.HH_ELITE_EXP15, ObjectID.HH_ELITE_EXP16, ObjectID.HH_MASTER001, ObjectID.HH_MASTER002,
        ObjectID.HH_MASTER003, ObjectID.HH_MASTER004, ObjectID.HH_MASTER005, ObjectID.HH_MASTER006,
        ObjectID.HH_MASTER007, ObjectID.HH_MASTER008, ObjectID.HH_MASTER009, ObjectID.HH_MASTER010,
        ObjectID.HH_MASTER011, ObjectID.HH_MASTER012, ObjectID.HH_MASTER013, ObjectID.HH_MASTER014,
        ObjectID.HH_MASTER015, ObjectID.HH_MASTER016, ObjectID.HH_MASTER017, ObjectID.HH_MASTER018,
        ObjectID.HH_MASTER019, ObjectID.HH_MASTER020, ObjectID.HH_MASTER021, ObjectID.HH_MEDIUM_EXP10,
        ObjectID.HH_BEGINNER001, ObjectID.HH_BEGINNER002, ObjectID.HH_BEGINNER003, ObjectID.HH_MASTER022,
        ObjectID.HH_ELITE_EXP17, ObjectID.HH_EASY_VM01, ObjectID.HH_ELITE_VM01, ObjectID.HH_MASTER_VM01,
        ObjectID.HH_MASTER_VM02, ObjectID.HH_MEDIUM_EXP11, ObjectID.HH_MEDIUM_EXP12, ObjectID.HH_HARD_EXP7,
        ObjectID.HH_EASY_SAIL, ObjectID.HH_ELITE_SAIL, ObjectID.HH_MASTER_SAIL
    };

    /** Runs one unit's check: 1 built, 0 not, anything else when the script didn't answer. */
    interface Check
    {
        int run(int objectId);
    }

    private String syncAccount;
    private int next;
    private final List<int[]> pending = new ArrayList<>();
    private JsonArray complete;
    private String completeAt;
    private String completeAccount;

    /** A sync starts; one already running for this character carries on. */
    void request(String account)
    {
        if (account == null || account.isBlank() || account.equals(syncAccount)) return;
        syncAccount = account;
        next = 0;
        pending.clear();
    }

    boolean running()
    {
        return syncAccount != null;
    }

    /** True once this session's sync finished. */
    boolean hasResult()
    {
        return complete != null;
    }

    /** One tick; true when this tick finished a sync with a changed result. */
    boolean tick(String account, Check check, Supplier<Instant> clock)
    {
        if (syncAccount == null) return false;
        if (account == null || !account.equals(syncAccount)) { abort(); return false; }
        for (int done = 0; done < BATCH && next < OBJECTS.length; done++, next++)
        {
            int built;
            try
            {
                built = check.run(OBJECTS[next]);
            }
            catch (RuntimeException failed)
            {
                built = -1;
            }
            if (built < 0 || built > 1)
            {
                abort();
                return false;
            }
            pending.add(new int[] { OBJECTS[next], built });
        }
        if (next < OBJECTS.length) return false;
        JsonArray rows = new JsonArray();
        for (int[] unit : pending)
        {
            JsonObject row = new JsonObject();
            row.addProperty("objectId", unit[0]);
            row.addProperty("built", unit[1] == 1);
            rows.add(row);
        }
        boolean changed = !account.equals(completeAccount) || complete == null || !complete.equals(rows);
        complete = rows;
        completeAccount = account;
        completeAt = ChangeAwareSourceCapture.time(clock.get());
        abort();
        return changed;
    }

    /** The last complete result of this character, or nothing read yet. */
    Observation observation(String account)
    {
        if (complete == null || account == null || !account.equals(completeAccount)) return Observation.notRead();
        return new Observation(complete.deepCopy(), completeAt);
    }

    private void abort()
    {
        syncAccount = null;
        next = 0;
        pending.clear();
    }

    /** A session or the data type ended: nothing carries over to another character. */
    void reset()
    {
        abort();
        complete = null;
        completeAt = null;
        completeAccount = null;
    }
}
