package com.sagaseer;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Upload triggers from the game's state rather than chat: a real level rising, and a Combat Achievement task word gaining a bit,
 * each against the session's baseline. They only ask for an upload.
 */
final class GameTriggers
{
    /** One completion sets one bit; more is the game re-sending the word. */
    private static final int MAX_NEW_TASK_BITS = 2;

    private final Map<String, Integer> knownLevels = new HashMap<>();
    private final Map<Integer, Integer> knownWords = new HashMap<>();

    /** A session end drops the baselines. */
    void sessionEnded()
    {
        knownLevels.clear();
        knownWords.clear();
    }

    /** Pause or a type turned off drops what that trigger held. */
    void drop(boolean paused, Set<String> off)
    {
        if (paused || off.contains("state.skills")) knownLevels.clear();
        if (paused || off.contains("state.combatAchievements")) knownWords.clear();
    }

    // ---- level-ups ----

    /** True when the real level differs from the one held. */
    boolean levelDiffers(String skill, int level)
    {
        Integer known = knownLevels.get(skill);
        return known == null || known != level;
    }

    /** True when the real level rose past the one held; the first sight is the baseline. */
    boolean levelUp(String skill, int level)
    {
        Integer known = knownLevels.put(skill, level);
        return known != null && level > known;
    }

    // ---- Combat Achievements ----

    /** The task-word varps (a lookup: varbit changes fire many times a tick). */
    private static final Set<Integer> TASK_WORDS = Arrays.stream(CombatAchievementsState.TASK_WORD_VARPS)
        .boxed().collect(Collectors.toUnmodifiableSet());

    static boolean isTaskWord(int varpId)
    {
        return TASK_WORDS.contains(varpId);
    }

    /** Once per session after login settles: the words arrive with no change event, so they are read. */
    void taskWordBaseline(IntUnaryOperator varp)
    {
        if (!knownWords.isEmpty()) return;
        for (int word : CombatAchievementsState.TASK_WORD_VARPS) knownWords.put(word, varp.applyAsInt(word));
    }

    /** True when the word gained one or two bits after the baseline. */
    boolean taskCompleted(int varpId, int value)
    {
        Integer known = knownWords.get(varpId);
        if (known == null) return false;
        knownWords.put(varpId, value);
        int gained = Integer.bitCount(value & ~known);
        return gained > 0 && gained <= MAX_NEW_TASK_BITS;
    }
}
