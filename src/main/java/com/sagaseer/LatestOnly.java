package com.sagaseer;

/** Lets only the newest generation through, so a sidebar queued before a reset never draws the old answer back. */
final class LatestOnly
{
    private boolean seen;
    private int newest;

    /** True when this generation is the newest so far (or equal). */
    synchronized boolean accept(int generation)
    {
        if (seen && generation < newest) return false;
        seen = true;
        newest = generation;
        return true;
    }
}
