package com.sagaseer;

final class DeferredSnapshotScheduler
{
    private String pendingReason;
    private long dueTick = -1L;

    void schedule(String reason, long currentTick, int settleTicks)
    {
        if (reason == null || reason.isBlank())
        {
            throw new IllegalArgumentException("Snapshot reason must not be blank");
        }

        if (settleTicks < 0)
        {
            throw new IllegalArgumentException("Snapshot settle ticks must not be negative");
        }

        long candidateDueTick = currentTick + settleTicks;

        if (pendingReason == null)
        {
            dueTick = candidateDueTick;
        }
        else
        {
            dueTick = Math.max(dueTick, candidateDueTick);
        }

        // A login or heartbeat request carries the permission to confirm
        // unchanged sections. Keep that reason if another event coalesces.
        if (!"LOGIN".equals(pendingReason) && !"HEARTBEAT".equals(pendingReason))
        {
            pendingReason = reason;
        }
    }

    boolean hasPending()
    {
        return pendingReason != null;
    }

    boolean isDue(long currentTick)
    {
        return pendingReason != null && currentTick >= dueTick;
    }

    String takeIfDue(long currentTick)
    {
        if (!isDue(currentTick))
        {
            return null;
        }

        String reason = pendingReason;
        clear();
        return reason;
    }

    String getPendingReason()
    {
        return pendingReason;
    }

    long getDueTick()
    {
        return dueTick;
    }

    void clear()
    {
        pendingReason = null;
        dueTick = -1L;
    }
}
