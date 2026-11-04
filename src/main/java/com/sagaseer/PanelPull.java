package com.sagaseer;

import java.time.Duration;
import java.time.Instant;

/**
 * When the side panel asks again: soon after login, a Slayer task change or an upload, at most once a minute; otherwise every 5
 * minutes.
 */
final class PanelPull
{
    static final Duration MIN_GAP = Duration.ofMinutes(1);
    static final Duration REFRESH = Duration.ofMinutes(5);

    private Instant last;
    private boolean wanted = true;

    /** Something changed that the read may show differently. */
    synchronized void want() { wanted = true; }

    synchronized boolean due(Instant now)
    {
        if (last != null && now.isBefore(last.plus(MIN_GAP))) return false;
        return wanted || last == null || !now.isBefore(last.plus(REFRESH));
    }

    /** A read was sent. */
    synchronized void started(Instant now)
    {
        last = now;
        wanted = false;
    }

    /** A new session or character: ask soon. */
    synchronized void reset()
    {
        last = null;
        wanted = true;
    }
}
