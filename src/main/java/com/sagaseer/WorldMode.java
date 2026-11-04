package com.sagaseer;

import java.util.EnumSet;
import java.util.Objects;
import net.runelite.api.WorldType;

/** A world is classified before any capture is allowed after login or a hop. */
final class WorldMode
{
    enum Mode
    {
        STANDARD, HARDCORE, LEAGUES, DEADMAN, NONE;

        boolean temporary() { return this == LEAGUES || this == DEADMAN; }
        /** What the sidebar adds after the name, or null on a normal world. */
        String label() { return this == LEAGUES ? "Leagues" : this == DEADMAN ? "Deadman" : this == HARDCORE ? "Hardcore Worlds" : null; }
        boolean recorded() { return this != NONE; }
    }

    static Mode of(EnumSet<WorldType> types)
    {
        if (types == null || types.contains(WorldType.BETA_WORLD) || types.contains(WorldType.TOURNAMENT_WORLD)
            || types.contains(WorldType.QUEST_SPEEDRUNNING) || types.contains(WorldType.NOSAVE_MODE)
            || types.contains(WorldType.FRESH_START_WORLD)) return Mode.NONE;
        if (types.contains(WorldType.HARDCORE_MAIN)) return Mode.HARDCORE;
        if (types.contains(WorldType.SEASONAL)) return Mode.LEAGUES;
        if (types.contains(WorldType.DEADMAN)) return Mode.DEADMAN;
        return Mode.STANDARD;
    }

    static final class Scope
    {
        final Mode mode;
        final Integer leagueType;

        Scope(Mode mode, Integer leagueType)
        {
            this.mode = Objects.requireNonNull(mode);
            this.leagueType = mode == Mode.LEAGUES && leagueType != null && leagueType > 0 ? leagueType : null;
        }

        String key(String name)
        {
            return mode == Mode.STANDARD ? name : name + "\n" + mode + ":" + (leagueType == null ? "" : leagueType);
        }

        /** A late league-type value fills an unknown, rather than starting another session. */
        boolean changedFrom(Scope old)
        {
            return old != null && (old.mode != mode ||
                (mode == Mode.LEAGUES && old.leagueType != null && leagueType != null
                    && !old.leagueType.equals(leagueType)));
        }

        @Override public boolean equals(Object other)
        {
            if (!(other instanceof Scope)) return false;
            Scope scope = (Scope) other;
            return scope.mode == mode && Objects.equals(scope.leagueType, leagueType);
        }

        @Override public int hashCode() { return Objects.hash(mode, leagueType); }
    }
}
