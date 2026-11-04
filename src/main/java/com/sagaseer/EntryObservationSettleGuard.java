package com.sagaseer;

final class EntryObservationSettleGuard
{
    private long settleUntilTick = -1L;

    void begin(long currentTick, int settleTicks)
    {
        if (settleTicks < 0)
        {
            throw new IllegalArgumentException("Entry settle ticks must not be negative");
        }

        settleUntilTick = currentTick + settleTicks;
    }

    boolean isSettling(long currentTick)
    {
        return settleUntilTick >= 0L &&
                currentTick < settleUntilTick;
    }

    int remainingTicks(long currentTick)
    {
        if (!isSettling(currentTick))
        {
            return 0;
        }

        return (int) (settleUntilTick - currentTick);
    }

    void clearIfSettled(long currentTick)
    {
        if (settleUntilTick >= 0L && currentTick >= settleUntilTick)
        {
            settleUntilTick = -1L;
        }
    }

    void reset()
    {
        settleUntilTick = -1L;
    }
}
