package com.sagaseer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately narrow parser for boss records in one tick's game messages, for one verified selector. Reads no client or stored
 * state.
 */
final class BossRecordChatParser
{
    enum Metric
    {
        KILL, COMPLETION
    }

    enum Channel
    {
        GAME_MESSAGE, OTHER
    }

    static final class Line
    {
        final String selector;
        final long tick;
        final Instant observedAt;
        final Channel channel;
        final String text;

        Line(String selector, long tick, Instant observedAt, Channel channel, String text)
        {
            this.selector = selector;
            this.tick = tick;
            this.observedAt = observedAt;
            this.channel = channel;
            this.text = text;
        }
    }

    static final class CountFact
    {
        final String activity;
        final Metric metric;
        final int reportedTotal;
        final Instant observedAt;
        /** Set only when the tick's single speed line carried a personal best. */
        final Long bestMillis;
        final String bestText;
        final Instant bestObservedAt;

        private CountFact(String activity, Metric metric, int reportedTotal, Instant observedAt,
                Long bestMillis, String bestText, Instant bestObservedAt)
        {
            this.activity = activity;
            this.metric = metric;
            this.reportedTotal = reportedTotal;
            this.observedAt = observedAt;
            this.bestMillis = bestMillis;
            this.bestText = bestText;
            this.bestObservedAt = bestObservedAt;
        }

        private CountFact withBest(UnattributedSpeedObservation speed)
        {
            return new CountFact(activity, metric, reportedTotal, observedAt,
                    speed.personalBestMillis, speed.personalBestText, speed.observedAt);
        }
    }

    /** Speed lines name a format, never a boss. */
    static final class UnattributedSpeedObservation
    {
        final String sourceFamily;
        final long durationMillis;
        final Long personalBestMillis;
        final String personalBestText;
        final Instant observedAt;

        private UnattributedSpeedObservation(String sourceFamily, long durationMillis,
                Long personalBestMillis, String personalBestText,
                Instant observedAt)
        {
            this.sourceFamily = sourceFamily;
            this.durationMillis = durationMillis;
            this.personalBestMillis = personalBestMillis;
            this.personalBestText = personalBestText;
            this.observedAt = observedAt;
        }
    }

    static final class Result
    {
        final List<CountFact> counts;
        final List<UnattributedSpeedObservation> unattributedSpeeds;

        private Result(List<CountFact> counts,
                List<UnattributedSpeedObservation> unattributedSpeeds)
        {
            this.counts = Collections.unmodifiableList(new ArrayList<>(counts));
            this.unattributedSpeeds = Collections.unmodifiableList(
                    new ArrayList<>(unattributedSpeeds));
        }
    }

    private static final int MAX_ACTIVITY_LENGTH = 100;
    private static final int MAX_COUNT = 10_000_000;
    private static final long MAX_BEST_MILLIS = 86_400_000L;
    /** A count and a speed line this close are one kill. */
    private static final Duration MAX_PAIR_GAP = Duration.ofSeconds(2);

    private static final String NUMBER = "(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)";
    private static final String TIME = "[0-9]+:[0-9]{2}(?::[0-9]{2})?(?:\\.[0-9]{1,2})?";
    private static final Pattern COLOR = Pattern.compile("(?i)</?col(?:=[0-9a-f]{6})?>");
    private static final Pattern COUNT = Pattern.compile(
            "^Your (.+?) (kill|completion) count is:\\s*(" + NUMBER + ")\\.?$",
            Pattern.CASE_INSENSITIVE);
    /** The raids' wordings, as RuneLite's Chat Commands matches them. */
    private static final Pattern COMPLETED = Pattern.compile(
            "^Your (?:completed (.+?) count|completion count for (.+?)) is:\\s*(" + NUMBER + ")\\.?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SPEED = Pattern.compile(
            "^(Fight duration|Challenge duration|Corrupted challenge duration|Duration):\\s*(" +
                    TIME + ")(?:\\. Personal best:\\s*(" + TIME + ")|\\s*(\\(new personal best\\)))?\\.?$",
            Pattern.CASE_INSENSITIVE);

    Result parseTick(String selector, long tick, List<Line> lines)
    {
        List<CountFact> counts = new ArrayList<>();
        List<UnattributedSpeedObservation> unattributedSpeeds = new ArrayList<>();
        if (selector == null || selector.isBlank() || tick < 0 || lines == null)
        {
            return new Result(counts, unattributedSpeeds);
        }

        for (Line line : lines)
        {
            if (line == null || line.channel != Channel.GAME_MESSAGE ||
                    line.observedAt == null || line.text == null ||
                    tick != line.tick || !selector.equals(line.selector))
            {
                continue;
            }

            String text = COLOR.matcher(line.text).replaceAll("").trim();
            // Ignore markup that wasn't explicitly understood.
            if (text.indexOf('<') >= 0 || text.indexOf('>') >= 0)
            {
                continue;
            }
            CountFact count = parseCount(text, line.observedAt);
            if (count != null)
            {
                counts.add(count);
                continue;
            }
            UnattributedSpeedObservation speed = parseSpeed(text, line.observedAt);
            if (speed != null)
            {
                unattributedSpeeds.add(speed);
            }
        }
        // Exactly one count and one close speed line with a best are one kill; anything else attributes nothing.
        if (counts.size() == 1 && unattributedSpeeds.size() == 1)
        {
            CountFact count = counts.get(0);
            UnattributedSpeedObservation speed = unattributedSpeeds.get(0);
            if (speed.personalBestMillis != null && speed.personalBestMillis <= MAX_BEST_MILLIS &&
                    Duration.between(count.observedAt, speed.observedAt).abs()
                            .compareTo(MAX_PAIR_GAP) <= 0)
            {
                counts.set(0, count.withBest(speed));
            }
        }
        return new Result(counts, unattributedSpeeds);
    }

    private static CountFact parseCount(String text, Instant observedAt)
    {
        Matcher count = COUNT.matcher(text);
        Matcher completed = COMPLETED.matcher(text);
        Metric metric;
        String activity;
        String number;
        if (count.matches())
        {
            metric = Metric.valueOf(count.group(2).toUpperCase(Locale.ROOT));
            activity = count.group(1);
            number = count.group(3);
        }
        else if (completed.matches())
        {
            metric = Metric.COMPLETION;
            activity = completed.group(1) != null ? completed.group(1) : completed.group(2);
            number = completed.group(3);
        }
        else
        {
            return null;
        }
        if (activity.isEmpty() || activity.length() > MAX_ACTIVITY_LENGTH)
        {
            return null;
        }
        try
        {
            long total = Long.parseLong(number.replace(",", ""));
            if (total <= MAX_COUNT)
            {
                return new CountFact(activity, metric, (int) total, observedAt, null, null, null);
            }
        }
        catch (NumberFormatException ignored)
        {
            // A malformed source value never becomes a reported total.
        }
        return null;
    }

    private static UnattributedSpeedObservation parseSpeed(String text, Instant observedAt)
    {
        Matcher speed = SPEED.matcher(text);
        if (!speed.matches())
        {
            return null;
        }
        Long duration = millis(speed.group(2));
        Long best = speed.group(3) != null ? millis(speed.group(3)) :
                speed.group(4) != null ? duration : null;
        if (duration == null || duration <= 0 ||
                (speed.group(3) != null && best == null) ||
                (best != null && (best <= 0 || best > duration)))
        {
            return null;
        }
        String bestText = speed.group(3) != null ? speed.group(3) :
                speed.group(4) != null ? speed.group(2) : null;
        return new UnattributedSpeedObservation(speed.group(1).toLowerCase(Locale.ROOT),
                duration, best, bestText, observedAt);
    }

    private static Long millis(String value)
    {
        try
        {
            String[] parts = value.split(":");
            long seconds;
            String finalPart;
            if (parts.length == 2)
            {
                seconds = Math.multiplyExact(Long.parseLong(parts[0]), 60);
                finalPart = parts[1];
            }
            else if (parts.length == 3 && Integer.parseInt(parts[1]) < 60)
            {
                seconds = Math.addExact(Math.multiplyExact(Long.parseLong(parts[0]), 3600),
                        Math.multiplyExact(Long.parseLong(parts[1]), 60));
                finalPart = parts[2];
            }
            else
            {
                return null;
            }
            String[] fraction = finalPart.split("\\.", -1);
            int wholeSeconds = Integer.parseInt(fraction[0]);
            if (wholeSeconds >= 60)
            {
                return null;
            }
            long result = Math.multiplyExact(Math.addExact(seconds, wholeSeconds), 1000);
            if (fraction.length == 2)
            {
                int centiseconds = Integer.parseInt(fraction[1]);
                result = Math.addExact(result, fraction[1].length() == 1 ?
                        centiseconds * 100L : centiseconds * 10L);
            }
            return result;
        }
        catch (NumberFormatException | ArithmeticException ignored)
        {
            return null;
        }
    }

}
