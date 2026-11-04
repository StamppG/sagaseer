package com.sagaseer;

import java.util.Set;
import net.runelite.client.util.Filepath;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * The sidebar's local view: the Recent feed of notable changes, the current Slayer task, and when each section was last seen.
 * Runs only when an upload is built, never per tick. The feed is kept per character on this computer (recent/<key>.json), linked
 * or not; while unlinked the pre-link history is kept too.
 */
@Slf4j
final class LocalTrail
{
    static final int MAX_EVENTS = 30;
    /** Sections whose first value is news: a raid row exists only once a chest was opened. */
    private static final Set<String> FIRST_IS_NEWS = Set.of("state.raidRewards");

    /**
     * One notable change: title, time and context. A kill count carries its boss, so one boss in one day is one row ("Vorkath 45
     * → 51 (+6)").
     */
    static final class Event
    {
        final String title;
        final String at;
        final List<String> details;
        final String merge;
        final Long from;
        final Long to;
        Event(String title, String at, List<String> details) { this(title, at, details, null, null, null); }
        Event(String title, String at, List<String> details, String merge, Long from, Long to)
        {
            this.title = title;
            this.at = at;
            this.details = details;
            this.merge = merge;
            this.from = from;
            this.to = to;
        }
    }

    static final class SlayerTask
    {
        final String name;
        final long remaining;
        final Long original;
        SlayerTask(String name, long remaining, Long original)
        {
            this.name = name;
            this.remaining = remaining;
            this.original = original;
        }
    }

    /** What the sidebar draws; replaced whole after each change. */
    static final class View
    {
        final String account;
        final List<Event> events;
        final Map<String, String> seenAt;
        final PrelinkBuffer.Summary kept;
        /** The current Slayer task, or null when none. */
        final SlayerTask slayer;
        /** The current Hunter rumour, or null when none is known. */
        final HunterRumourDialogue.Rumour rumour;
        View(String account, List<Event> events, Map<String, String> seenAt, PrelinkBuffer.Summary kept, SlayerTask slayer,
             HunterRumourDialogue.Rumour rumour)
        {
            this.account = account;
            this.events = events;
            this.seenAt = seenAt;
            this.kept = kept;
            this.slayer = slayer;
            this.rumour = rumour;
        }
    }

    private final PrelinkBuffer buffer;
    private final Filepath recentDirectory;
    private final Gson gson;
    private final ExecutorService work = Executors.newSingleThreadExecutor(task ->
    {
        Thread thread = new Thread(task, "sagaseer-local");
        thread.setDaemon(true);
        return thread;
    });
    private String account;
    private final Map<String, JsonArray> latest = new HashMap<>();
    private final Map<String, String> seenAt = new LinkedHashMap<>();
    private final Deque<Event> events = new ArrayDeque<>();
    private volatile View view = new View(null, List.of(), Map.of(), null, null, null);
    private volatile Runnable onView = () -> { };

    LocalTrail(PrelinkBuffer buffer, Filepath recentDirectory, Gson gson)
    {
        this.buffer = buffer;
        this.recentDirectory = recentDirectory;
        this.gson = gson;
    }

    View view() { return view; }
    void onView(Runnable listener) { onView = listener; }

    /** The sections read at a trigger. keep: not linked and not paused, so pre-link sections go to disk. */
    void observe(String character, Map<String, ChangeAwareSourceCapture.Observation> observations, boolean keep)
    {
        Map<String, ChangeAwareSourceCapture.Observation> reads = new LinkedHashMap<>(observations);
        work.execute(() ->
        {
            try
            {
                switchTo(character);
                boolean changed = false;
                for (Map.Entry<String, ChangeAwareSourceCapture.Observation> read : reads.entrySet())
                {
                    String section = read.getKey();
                    ChangeAwareSourceCapture.Observation observation = read.getValue();
                    JsonArray before = latest.get(section);
                    JsonArray after = observation.mergeOnto != null
                        ? observation.mergeOnto.apply(before == null ? null : before.deepCopy())
                        : observation.value == null ? null : observation.value.deepCopy();
                    if (after == null || observation.sourceObservedAt == null) continue;
                    seenAt.put(section, observation.sourceObservedAt);
                    if (after.equals(before)) continue;
                    JsonArray from = before != null ? before : FIRST_IS_NEWS.contains(section) ? new JsonArray() : null;
                    if (from != null) changed |= remember(LocalEvents.between(section, from, after, observation.sourceObservedAt));
                    latest.put(section, after);
                    if (keep) buffer.append(character, section, after, observation.sourceObservedAt);
                }
                if (changed) saveRecent();
                publish();
            }
            catch (IOException | RuntimeException failed)
            {
                log.debug("SagaSeer local history unavailable", failed);
            }
        });
    }

    /** Clear local data: the pre-link history, the Recent feeds and the changes shown. */
    void clear()
    {
        work.execute(() ->
        {
            try
            {
                buffer.clearAll();
                for (Filepath path : PrelinkBuffer.files(recentDirectory, ".json")) path.deleteIfExists();
            }
            catch (IOException failed) { log.warn("Could not delete SagaSeer's local data"); }
            events.clear();
            publish();
        });
    }

    /** Asks for a fresh view, for example after the link or a setting changed. */
    void refresh() { work.execute(this::publish); }

    void shutdown() { work.shutdownNow(); }

    void awaitIdleForTest() throws Exception { work.submit(() -> { }).get(10, TimeUnit.SECONDS); }

    /**
     * A new character starts from its own kept feed, else from the pre-link history's changes; the latest values come back from
     * that history when there is one.
     */
    private void switchTo(String character) throws IOException
    {
        if (character.equals(account)) return;
        account = character;
        latest.clear();
        seenAt.clear();
        events.clear();
        boolean saved = loadRecent();
        buffer.replay(character, (section, before, after, at) ->
        {
            JsonArray from = before != null ? before : FIRST_IS_NEWS.contains(section) ? new JsonArray() : null;
            if (from != null && !saved) remember(LocalEvents.between(section, from, after, at));
            latest.put(section, after);
            seenAt.put(section, at);
        });
        // Kept now, since importing the history deletes it.
        if (!saved && !events.isEmpty()) saveRecent();
    }

    /**
     * Adds the newest events in front: a same-day kill count replaces its row, keeping the first count; a log item isn't shown
     * twice ("Pet: …" replaces the plain row). True when something changed.
     */
    boolean remember(List<Event> found)
    {
        boolean changed = false;
        for (Event event : found)
        {
            String item = logItem(event.title);
            if (item != null)
            {
                Event shown = null;
                for (Event earlier : events) if (item.equals(logItem(earlier.title))) { shown = earlier; break; }
                if (shown != null && (shown.title.startsWith("Pet: ") || !event.title.startsWith("Pet: "))) continue;
                if (shown != null) events.remove(shown);
            }
            changed = true;
            Event merged = event;
            if (event.merge != null)
                for (Iterator<Event> it = events.iterator(); it.hasNext(); )
                {
                    Event earlier = it.next();
                    if (!event.merge.equals(earlier.merge) || !Objects.equals(day(earlier.at), day(event.at))) continue;
                    it.remove();
                    Long from = earlier.from != null ? earlier.from : event.from;
                    String boss = event.merge.substring(event.merge.indexOf(':') + 1);
                    merged = new Event(LocalEvents.killCountTitle(boss, from, event.to), event.at, event.details, event.merge, from, event.to);
                    break;
                }
            events.addFirst(merged);
            while (events.size() > MAX_EVENTS) events.removeLast();
        }
        return changed;
    }

    /** The item of a Collection Log or pet row, or null for any other row. */
    private static String logItem(String title)
    {
        if (title.startsWith("Collection log: ")) return title.substring("Collection log: ".length());
        if (title.startsWith("Pet: ")) return title.substring("Pet: ".length());
        return null;
    }

    private static String day(String at)
    {
        try { return Instant.parse(at).atZone(ZoneId.systemDefault()).toLocalDate().toString(); }
        catch (RuntimeException unreadable) { return at; }
    }

    private Filepath recentFile() { return recentDirectory.joinSegment(PrelinkBuffer.key(account) + ".json"); }

    /** Reads the kept feed; false when there is none. */
    private boolean loadRecent()
    {
        Filepath file = recentFile();
        if (!file.isFile()) return false;
        try
        {
            JsonArray rows = new JsonParser().parse(PrelinkBuffer.text(file)).getAsJsonArray();
            for (JsonElement element : rows)
            {
                JsonObject row = element.getAsJsonObject();
                // Older plugins kept music unlocks in this file; they are not events any more.
                if (row.get("title").getAsString().startsWith("Music unlocked: ")) continue;
                List<String> details = new ArrayList<>();
                for (JsonElement line : row.getAsJsonArray("details")) details.add(line.getAsString());
                events.addLast(new Event(row.get("title").getAsString(), row.get("at").getAsString(), details,
                    row.has("merge") ? row.get("merge").getAsString() : null,
                    row.has("from") ? row.get("from").getAsLong() : null, row.has("to") ? row.get("to").getAsLong() : null));
                if (events.size() >= MAX_EVENTS) break;
            }
            return true;
        }
        catch (IOException | RuntimeException unreadable)
        {
            events.clear();
            log.debug("SagaSeer recent changes unreadable; starting fresh");
            return false;
        }
    }

    /** Writes the feed (newest first) in one step. */
    private void saveRecent() throws IOException
    {
        if (account == null) return;
        JsonArray rows = new JsonArray();
        for (Event event : events)
        {
            JsonObject row = new JsonObject();
            row.addProperty("title", event.title);
            row.addProperty("at", event.at);
            JsonArray details = new JsonArray();
            for (String line : event.details) details.add(line);
            row.add("details", details);
            if (event.merge != null) row.addProperty("merge", event.merge);
            if (event.from != null) row.addProperty("from", event.from);
            if (event.to != null) row.addProperty("to", event.to);
            rows.add(row);
        }
        recentDirectory.createDirectories();
        Filepath file = recentFile(), temp = recentDirectory.joinSegment(file.getFileName() + ".tmp");
        temp.write(gson.toJson(rows).getBytes(StandardCharsets.UTF_8));
        temp.moveTo(file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void publish()
    {
        PrelinkBuffer.Summary kept = null;
        try { if (account != null) kept = buffer.summary(account); }
        catch (IOException ignored) { /* Shown as nothing kept. */ }
        view = new View(account, new ArrayList<>(events), new LinkedHashMap<>(seenAt), kept, slayerTask(latest.get("state.slayer")),
            rumour(latest.get("state.hunterRumours")));
        onView.run();
    }

    /** The task from the Slayer read, or null without one. */
    static SlayerTask slayerTask(JsonArray slayer)
    {
        try
        {
            JsonObject row = first(slayer);
            if (row == null || !row.get("assignment").isJsonObject()) return null;
            JsonObject task = row.getAsJsonObject("assignment");
            if (!task.has("name") || task.get("name").isJsonNull()) return null;
            Long original = task.has("original") && !task.get("original").isJsonNull() ? task.get("original").getAsLong() : null;
            return new SlayerTask(task.get("name").getAsString(), task.get("remaining").getAsLong(), original);
        }
        catch (RuntimeException unreadable) { return null; }
    }

    /** This session's view, with the last synced rumour when this session hasn't heard one (a Guild Hunter talk is needed). */
    static View withSynced(View view, JsonArray syncedRumours)
    {
        if (view.rumour != null || syncedRumours == null) return view;
        HunterRumourDialogue.Rumour synced = rumour(syncedRumours);
        return synced == null ? view : new View(view.account, view.events, view.seenAt, view.kept, view.slayer, synced);
    }

    /** The current rumour from the Hunter rumours read, or null without one. */
    static HunterRumourDialogue.Rumour rumour(JsonArray rumours)
    {
        try
        {
            JsonObject row = first(rumours);
            if (row == null || !row.has("current") || !row.get("current").isJsonObject()) return null;
            JsonObject current = row.getAsJsonObject("current");
            return new HunterRumourDialogue.Rumour(current.get("hunter").getAsString(), current.get("tier").getAsString(),
                current.get("creature").getAsString());
        }
        catch (RuntimeException unreadable) { return null; }
    }

    /** Small reads of a section's single row. */
    static JsonObject first(JsonArray rows)
    {
        if (rows == null || rows.size() == 0) return null;
        JsonElement row = rows.get(0);
        return row != null && row.isJsonObject() ? row.getAsJsonObject() : null;
    }
}
