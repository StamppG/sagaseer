package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Boss records seen in chat for one authorized selector. A tick's lines are read together so a personal best pairs with that
 * tick's one count; new facts merge onto the latest state.bosses value.
 */
final class BossRecordChatCapture
{
    private static final int MAX_OBSERVATIONS_PER_KIND = 16;
    private static final int MAX_LINES_PER_TICK = 32;
    private static final int MAX_ROWS = 500;

    private final BossRecordChatParser parser = new BossRecordChatParser();
    private final List<BossRecordChatParser.CountFact> counts = new ArrayList<>();
    private final List<BossRecordChatParser.Line> tickLines = new ArrayList<>();
    private long tickLinesTick = -1;
    private String selector;

    void observeIdentity(String currentSelector, boolean allowed)
    {
        if (!allowed || currentSelector == null || currentSelector.isBlank())
        {
            reset();
            return;
        }
        if (!currentSelector.equals(selector))
        {
            reset();
            selector = currentSelector;
        }
    }

    boolean accept(String currentSelector, boolean allowed, long tick, Instant observedAt,
            String rawMessage)
    {
        observeIdentity(currentSelector, allowed);
        if (selector == null)
        {
            return false;
        }
        if (tick != tickLinesTick)
        {
            settleTick();
            tickLinesTick = tick;
        }
        if (tickLines.size() >= MAX_LINES_PER_TICK) return false;
        List<BossRecordChatParser.CountFact> before = parseTickLines();
        tickLines.add(new BossRecordChatParser.Line(selector, tick, observedAt,
                BossRecordChatParser.Channel.GAME_MESSAGE, rawMessage));
        List<BossRecordChatParser.CountFact> after = parseTickLines();
        if (after.size() > before.size()) return true;
        return after.size() == 1 && before.size() == 1 &&
                before.get(0).bestMillis == null && after.get(0).bestMillis != null;
    }

    /** The finished tick's lines become counts. */
    private void settleTick()
    {
        for (BossRecordChatParser.CountFact count : parseTickLines())
        {
            addBounded(counts, count);
        }
        tickLines.clear();
        tickLinesTick = -1;
    }

    private List<BossRecordChatParser.CountFact> parseTickLines()
    {
        return parser.parseTick(selector, tickLinesTick, tickLines).counts;
    }

    /** Counts since the last upload, newest last, including a tick in progress. */
    List<BossRecordChatParser.CountFact> pendingCounts(String currentSelector, boolean allowed)
    {
        observeIdentity(currentSelector, allowed);
        List<BossRecordChatParser.CountFact> pending = new ArrayList<>(counts);
        if (selector != null && !tickLines.isEmpty())
        {
            for (BossRecordChatParser.CountFact count : parseTickLines())
            {
                addBounded(pending, count);
            }
        }
        return pending;
    }

    /** Like pendingCounts, and they are not seen again. */
    List<BossRecordChatParser.CountFact> takeCounts(String currentSelector, boolean allowed)
    {
        List<BossRecordChatParser.CountFact> taken = pendingCounts(currentSelector, allowed);
        counts.clear();
        tickLines.clear();
        tickLinesTick = -1;
        return taken;
    }

    /** Merges these facts onto the latest rows; null when none. */
    static ChangeAwareSourceCapture.Observation observation(
            List<BossRecordChatParser.CountFact> facts)
    {
        if (facts.isEmpty())
        {
            return null;
        }
        Instant newest = facts.get(0).observedAt;
        for (BossRecordChatParser.CountFact fact : facts)
        {
            if (fact.observedAt.isAfter(newest))
            {
                newest = fact.observedAt;
            }
        }
        return ChangeAwareSourceCapture.Observation.merging(
                previous -> merge(previous, facts), ChangeAwareSourceCapture.time(newest));
    }

    /** A new line replaces the row's count; an attributed best replaces the best. */
    static JsonArray merge(JsonArray previous, List<BossRecordChatParser.CountFact> facts)
    {
        List<JsonObject> rows = new ArrayList<>();
        if (previous != null)
        {
            for (JsonElement row : previous)
            {
                rows.add(row.getAsJsonObject());
            }
        }
        for (BossRecordChatParser.CountFact fact : facts)
        {
            JsonObject row = null;
            for (JsonObject candidate : rows)
            {
                if (fact.activity.equals(candidate.get("activity").getAsString()))
                {
                    row = candidate;
                }
            }
            if (row == null)
            {
                if (rows.size() >= MAX_ROWS)
                {
                    continue;
                }
                row = new JsonObject();
                row.addProperty("activity", fact.activity);
                row.add("metric", JsonNull.INSTANCE);
                row.add("count", JsonNull.INSTANCE);
                row.add("countObservedAt", JsonNull.INSTANCE);
                row.add("bestMillis", JsonNull.INSTANCE);
                row.add("bestText", JsonNull.INSTANCE);
                row.add("bestObservedAt", JsonNull.INSTANCE);
                rows.add(row);
            }
            row.addProperty("metric", fact.metric.name());
            row.addProperty("count", fact.reportedTotal);
            row.addProperty("countObservedAt", ChangeAwareSourceCapture.time(fact.observedAt));
            if (fact.bestMillis != null)
            {
                row.addProperty("bestMillis", fact.bestMillis);
                row.addProperty("bestText", fact.bestText);
                row.addProperty("bestObservedAt", ChangeAwareSourceCapture.time(fact.bestObservedAt));
            }
        }
        rows.sort((x, y) -> x.get("activity").getAsString().compareTo(y.get("activity").getAsString()));
        JsonArray merged = new JsonArray();
        for (JsonObject row : rows)
        {
            merged.add(row);
        }
        return merged;
    }

    boolean hasEvidence()
    {
        return !counts.isEmpty() || !tickLines.isEmpty();
    }

    void reset()
    {
        selector = null;
        counts.clear();
        tickLines.clear();
        tickLinesTick = -1;
    }

    private static <T> void addBounded(List<T> values, T value)
    {
        if (values.size() == MAX_OBSERVATIONS_PER_KIND)
        {
            values.remove(0);
        }
        values.add(value);
    }
}
