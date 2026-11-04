package com.sagaseer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collection Log changes waiting to upload for one character, and the controls of a full sync. Loading screens don't touch it: a
 * session end suspends a sync, and only another character clears what waits.
 */
final class CollectionLogAccountState
{
    private static final int MAX_PENDING_CHANGES = 256;

    final Map<Integer, Integer> instantItems = new LinkedHashMap<>();
    private final List<CollectionLogRows.Change> pending = new ArrayList<>();

    boolean notificationStarted;
    boolean instantRetrieval;
    int instantLastTransmitTick = -1;
    long lastCaptureTime;

    private String ownerName;
    void reset()
    {
        ownerName = null;
        clearCapture();
    }

    boolean observeIdentity(String name, boolean loggedIn)
    {
        if (!loggedIn || name == null || name.isBlank())
        {
            suspend();
            return false;
        }

        String observedName = name.trim();
        if (ownerName != null && !ownerName.equalsIgnoreCase(observedName))
        {
            clearCapture();
        }
        ownerName = observedName;
        return true;
    }

    /** Queues a change for this character's next upload. */
    boolean addChange(String account, boolean loggedIn, CollectionLogRows.Change change)
    {
        if (!observeIdentity(account, loggedIn))
        {
            return false;
        }
        if (pending.size() == MAX_PENDING_CHANGES)
        {
            pending.remove(0);
        }
        pending.add(change);
        return true;
    }

    /** Takes this character's waiting changes. */
    List<CollectionLogRows.Change> takeChanges(String account, boolean loggedIn)
    {
        if (!observeIdentity(account, loggedIn))
        {
            return new ArrayList<>();
        }
        List<CollectionLogRows.Change> taken = new ArrayList<>(pending);
        pending.clear();
        return taken;
    }

    void startNotification(String account, boolean loggedIn)
    {
        if (observeIdentity(account, loggedIn))
        {
            notificationStarted = true;
        }
    }

    boolean consumeNotificationDelay(String account, boolean loggedIn)
    {
        if (!observeIdentity(account, loggedIn))
        {
            return false;
        }
        boolean started = notificationStarted;
        notificationStarted = false;
        return started;
    }

    boolean beginInstant(String account, boolean loggedIn)
    {
        if (!observeIdentity(account, loggedIn) || instantRetrieval)
        {
            return false;
        }
        instantRetrieval = true;
        instantLastTransmitTick = -1;
        instantItems.clear();
        return true;
    }

    boolean acceptInstant(String account, boolean loggedIn,
            int itemId, int quantity, int tick)
    {
        if (!observeIdentity(account, loggedIn) || !instantRetrieval)
        {
            return false;
        }
        instantLastTransmitTick = tick;
        instantItems.put(itemId, quantity);
        return true;
    }

    /** Two quiet ticks after the last streamed item, the sync becomes a change. */
    boolean completeInstant(String account, boolean loggedIn,
            int currentTick, Instant at, CollectionLogLayout layout)
    {
        if (!observeIdentity(account, loggedIn) || !instantRetrieval ||
                instantLastTransmitTick == -1 ||
                instantLastTransmitTick + 2 >= currentTick)
        {
            return false;
        }
        addChange(account, loggedIn, new CollectionLogRows.FullSync(at, instantItems, layout));
        instantRetrieval = false;
        instantLastTransmitTick = -1;
        return true;
    }

    private void suspend()
    {
        // Keep waiting changes for a same-character hop; drop callbacks in progress.
        notificationStarted = false;
        lastCaptureTime = 0;
        if (instantRetrieval)
        {
            instantItems.clear();
        }
        instantRetrieval = false;
        instantLastTransmitTick = -1;
    }

    private void clearCapture()
    {
        pending.clear();
        instantItems.clear();
        notificationStarted = false;
        instantRetrieval = false;
        instantLastTransmitTick = -1;
        lastCaptureTime = 0;
    }
}
