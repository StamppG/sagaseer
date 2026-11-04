package com.sagaseer;

import net.runelite.api.HitsplatID;
import net.runelite.client.util.Text;

/**
 * What the player was fighting when they died: the NPC they were attacking, when a damaging hit landed that tick or the one
 * before (damage over time counts); with no target, the NPC they left in the last minute. It names what the player fought,
 * never who killed them; who an NPC targets is never read (Hub rule: no NPC focus identification). Anything else is null, never a
 * guess.
 */
final class DeathCause
{
    static final int NAME_MAX = 60;
    /** The hit that killed the player lands on the death tick or the tick before it. */
    static final int HIT_WINDOW_TICKS = 1;
    /** How long a left target still counts (100 ticks, a minute): long enough for a slow kill after you stop attacking. */
    static final int TARGET_MEMORY_TICKS = 100;
    private static final int NEVER = Integer.MIN_VALUE / 2;

    private int lastHitTick = NEVER;
    private String target;
    private String previousTarget;
    private int previousTargetTick = NEVER;

    /** Nothing carries over a login, a world hop or the plugin being turned off. */
    void reset()
    {
        lastHitTick = NEVER;
        target = null;
        previousTarget = null;
        previousTargetTick = NEVER;
    }

    /** The player's target changed: the NPC's name, or null. */
    void targeting(int tick, String npc)
    {
        if (target != null)
        {
            previousTarget = target;
            previousTargetTick = tick;
        }
        target = npc;
    }

    /** True for a hit that took health: not a block, healing or a drain. */
    static boolean damage(int hitsplatType, int amount)
    {
        if (amount <= 0) return false;
        switch (hitsplatType)
        {
            case HitsplatID.BLOCK_ME: case HitsplatID.BLOCK_OTHER: case HitsplatID.DISEASE_BLOCKED:
            case HitsplatID.HEAL: case HitsplatID.PRAYER_DRAIN: case HitsplatID.SANITY_DRAIN: case HitsplatID.SANITY_RESTORE:
            case HitsplatID.CORRUPTION:
                return false;
            default:
                return true;
        }
    }

    /** A damaging hit this tick. */
    void hit(int tick)
    {
        lastHitTick = tick;
    }

    /** The opponent at the death tick: the current NPC target, else the one last left. */
    String opponent(int tick, String target)
    {
        if (tick < lastHitTick || tick - lastHitTick > HIT_WINDOW_TICKS) return null;
        if (target != null) return clean(target);
        return previousTarget != null && tick >= previousTargetTick && tick - previousTargetTick <= TARGET_MEMORY_TICKS ? clean(previousTarget) : null;
    }

    /** A game name without tags or odd spaces; null when empty or too long. */
    static String clean(String name)
    {
        if (name == null) return null;
        String plain = Text.removeTags(name).replace(' ', ' ').trim();
        if (plain.isEmpty() || plain.length() > NAME_MAX || plain.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) return null;
        return plain;
    }
}
