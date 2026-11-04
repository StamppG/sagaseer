package com.sagaseer;

import java.io.InputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Consumer;

/**
 * Pre-link history: while not linked, recent changes are kept on this computer only, one JSON-lines file per character in
 * .runelite/sagaseer/prelink, and uploaded only if the player links and chooses Import History. Line 1 is a header; each later
 * line is one change with the section's whole value, or only the changed rows for a keyed section already in the file. Only
 * SyncPolicy.PRELINK_SECTIONS, at most MAX_AGE and MAX_BYTES (oldest first out, flagged droppedOldest).
 */
final class PrelinkBuffer
{
    static final String FORMAT = "sagaseer.prelink.v1";
    static final Duration MAX_AGE = Duration.ofDays(90);
    static final long MAX_BYTES = 8L * 1024 * 1024;
    static final int MAX_BATCH_BYTES = 4 * 1024 * 1024 - 1024;

    /** What the file holds, for the sidebar and the website's offer. */
    static final class Summary
    {
        final int records;
        final String from;
        final String to;
        final Set<String> sections;
        final boolean droppedOldest;
        Summary(int records, String from, String to, Set<String> sections, boolean droppedOldest)
        {
            this.records = records;
            this.from = from;
            this.to = to;
            this.sections = sections;
            this.droppedOldest = droppedOldest;
        }
        JsonObject toJson()
        {
            JsonObject json = new JsonObject();
            json.addProperty("records", records);
            json.addProperty("from", from);
            json.addProperty("to", to);
            JsonArray names = new JsonArray();
            for (String section : sections) names.add(section);
            json.add("sections", names);
            json.addProperty("droppedOldest", droppedOldest);
            return json;
        }
    }

    /** Each change as a file replays: section, value before (null at first) and after, and when. */
    interface Replay
    {
        void change(String section, JsonArray before, JsonArray after, String at);
    }

    private final Filepath directory;
    private final Gson gson;
    private final Supplier<Instant> clock;
    private String loadedAccount;
    private JsonObject header;
    private final List<JsonObject> records = new ArrayList<>();
    /** Each section's value after the last record. */
    private final Map<String, JsonArray> state = new HashMap<>();
    private final Set<String> warnedTooLarge = new HashSet<>();
    private long bytes;

    PrelinkBuffer(Filepath directory, Gson gson, Supplier<Instant> clock)
    {
        this.directory = directory;
        // Rows hold nulls the server expects to see.
        this.gson = gson.newBuilder().serializeNulls().create();
        this.clock = clock;
    }

    /** Keeps one change of a pre-link section; nothing when it equals the file's latest. */
    synchronized void append(String account, String section, JsonArray after, String at) throws IOException
    {
        if (!SyncPolicy.PRELINK_SECTIONS.contains(section) || after == null || at == null) return;
        load(account);
        JsonArray before = state.get(section);
        if (after.equals(before)) return;
        JsonObject record = new JsonObject();
        record.addProperty("id", UUID.randomUUID().toString());
        record.addProperty("at", at);
        record.addProperty("section", section);
        JsonArray rows = changedRows(section, before, after);
        if (rows != null) record.add("rows", rows);
        else record.add("value", after.deepCopy());
        if (header == null)
        {
            header = new JsonObject();
            header.addProperty("format", FORMAT);
            header.addProperty("account", account);
            header.addProperty("startedAt", at);
            header.addProperty("droppedOldest", false);
            write();
        }
        byte[] line = (gson.toJson(record) + "\n").getBytes(StandardCharsets.UTF_8);
        file(account).write(line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        records.add(record);
        state.put(section, after.deepCopy());
        bytes += line.length;
        Instant oldest = Instant.parse(records.get(0).get("at").getAsString());
        if (bytes > MAX_BYTES || oldest.isBefore(clock.get().minus(MAX_AGE))) compact();
    }

    /** Null when nothing is kept for the character. */
    synchronized Summary summary(String account) throws IOException
    {
        return summary(account, Set.of());
    }

    /**
     * What would be imported now (sections not in off), from the earliest to the latest source time (file order isn't time
     * order). Null when none.
     */
    synchronized Summary summary(String account, Set<String> off) throws IOException
    {
        load(account);
        Set<String> sections = new TreeSet<>();
        Instant from = null, to = null;
        String fromText = null, toText = null;
        int count = 0;
        for (JsonObject record : records)
        {
            String section = record.get("section").getAsString();
            if (off.contains(section)) continue;
            sections.add(section);
            count++;
            String text = record.get("at").getAsString();
            Instant at = Instant.parse(text);
            if (from == null || at.isBefore(from)) { from = at; fromText = text; }
            if (to == null || at.isAfter(to)) { to = at; toText = text; }
        }
        if (count == 0) return null;
        return new Summary(count, fromText, toText, sections, header.get("droppedOldest").getAsBoolean());
    }

    /** Every change, oldest first, with whole values. */
    synchronized void replay(String account, Replay replay) throws IOException
    {
        load(account);
        Map<String, JsonArray> replayed = new HashMap<>();
        for (JsonObject record : records)
        {
            String section = record.get("section").getAsString();
            JsonArray after = apply(section, replayed.get(section), record);
            replay.change(section, replayed.get(section), after, record.get("at").getAsString());
            replayed.put(section, after);
        }
    }

    /** Batches cut by count and exact POST bytes; the cuts depend only on the file and off set. */
    synchronized List<JsonArray> batches(String account, int size) throws IOException
    {
        return batches(account, size, Set.of());
    }

    /** As above, leaving out the sections in off. */
    synchronized List<JsonArray> batches(String account, int size, Set<String> off) throws IOException
    {
        return batches(account, size, off, section -> { });
    }

    synchronized List<JsonArray> batches(String account, int size, Set<String> off, Consumer<String> tooLarge) throws IOException
    {
        if (size < 1 || size > 50) throw new IllegalArgumentException("Invalid pre-link batch size");
        load(account);
        List<JsonArray> batches = new ArrayList<>();
        Map<String, JsonArray> replayed = new HashMap<>();
        Set<String> seen = new HashSet<>();
        JsonArray batch = new JsonArray();
        // The body's exact size as records are added.
        long batchBytes = bodyBytes(batch);
        for (JsonObject record : records)
        {
            String section = record.get("section").getAsString();
            if (off.contains(section)) continue;
            JsonArray after = apply(section, replayed.get(section), record);
            replayed.put(section, after);
            JsonObject sent = forBatch(record, after, seen);
            long sentBytes = recordBytes(sent);
            if (batch.size() >= size || withRecord(batchBytes, batch.size(), sentBytes) > MAX_BATCH_BYTES)
            {
                if (batch.size() > 0) batches.add(batch);
                batch = new JsonArray();
                batchBytes = bodyBytes(batch);
                seen.clear();
                sent = forBatch(record, after, seen);
                sentBytes = recordBytes(sent);
            }
            if (withRecord(batchBytes, batch.size(), sentBytes) > MAX_BATCH_BYTES)
            {
                seen.remove(section);
                if (warnedTooLarge.add(record.get("id").getAsString())) tooLarge.accept(section);
                continue;
            }
            batchBytes = withRecord(batchBytes, batch.size(), sentBytes);
            batch.add(sent);
            seen.add(section);
        }
        if (batch.size() > 0) batches.add(batch);
        return batches;
    }

    private JsonObject forBatch(JsonObject record, JsonArray after, Set<String> seen)
    {
        JsonObject sent = record.deepCopy();
        if (!seen.contains(record.get("section").getAsString()) && sent.has("rows"))
        {
            sent.remove("rows");
            sent.add("value", after.deepCopy());
        }
        return sent;
    }

    private long recordBytes(JsonObject record)
    {
        return gson.toJson(record).getBytes(StandardCharsets.UTF_8).length;
    }

    private static long withRecord(long bytes, int count, long record)
    {
        return bytes + record + (count > 0 ? 1 : 0);
    }

    int bodyBytes(JsonArray batch)
    {
        JsonObject body = new JsonObject();
        body.add("records", batch);
        return gson.toJson(body).getBytes(StandardCharsets.UTF_8).length;
    }

    /** Deletes one character's file (after an import or Start Fresh). */
    synchronized void clear(String account) throws IOException
    {
        file(account).deleteIfExists();
        if (key(account).equals(loadedAccount)) forget();
    }

    /** Clear local data: every character's file. */
    synchronized void clearAll() throws IOException
    {
        for (Filepath path : files(directory, ".jsonl")) path.deleteIfExists();
        forget();
    }

    /** The file name hashes the character's name, so no name is in a path. */
    static String key(String account)
    {
        return SagaSeerLink.sha256Hex(account.trim().toLowerCase(Locale.ROOT)).substring(0, 16);
    }

    private Filepath file(String account) { return directory.joinSegment(key(account) + ".jsonl"); }

    /** Files directly in a folder ending with `suffix` (none without the folder). */
    static List<Filepath> files(Filepath folder, String suffix) throws IOException
    {
        if (!folder.isDirectory()) return List.of();
        try (Stream<Filepath> found = folder.walk(1))
        {
            return found.filter(path -> !path.equals(folder) && path.getFileName().endsWith(suffix)).collect(Collectors.toList());
        }
    }

    /** A whole file as text (UTF-8). */
    static String text(Filepath file) throws IOException
    {
        try (InputStream in = file.openInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private void forget()
    {
        loadedAccount = null;
        header = null;
        records.clear();
        state.clear();
        warnedTooLarge.clear();
        bytes = 0;
    }

    /** Reads the character's file once; a foreign format or broken line sets it aside. */
    private void load(String account) throws IOException
    {
        if (key(account).equals(loadedAccount)) return;
        forget();
        loadedAccount = key(account);
        Filepath path = file(account);
        if (!path.exists()) return;
        List<String> lines = List.of(text(path).split("\n"));
        try
        {
            JsonObject first = new JsonParser().parse(lines.get(0)).getAsJsonObject();
            if (!FORMAT.equals(first.get("format").getAsString())) throw new IllegalStateException("format");
            header = first;
            for (String line : lines.subList(1, lines.size()))
            {
                if (line.isBlank()) continue;
                JsonObject record = new JsonParser().parse(line).getAsJsonObject();
                String section = record.get("section").getAsString();
                state.put(section, apply(section, state.get(section), record));
                records.add(record);
                bytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
            }
            bytes += lines.get(0).getBytes(StandardCharsets.UTF_8).length + 1;
        }
        catch (RuntimeException unreadable)
        {
            path.moveTo(directory.joinSegment(path.getFileName() + ".unreadable"), StandardCopyOption.REPLACE_EXISTING);
            forget();
            loadedAccount = key(account);
        }
    }

    /**
     * Drops records past MAX_AGE, then the oldest until well under MAX_BYTES; each section's first remaining record becomes whole
     * again.
     */
    private void compact() throws IOException
    {
        Instant cutoff = clock.get().minus(MAX_AGE);
        Map<String, JsonArray> replayed = new HashMap<>();
        List<JsonObject> whole = new ArrayList<>();
        for (JsonObject record : records)
        {
            String section = record.get("section").getAsString();
            JsonArray after = apply(section, replayed.get(section), record);
            replayed.put(section, after);
            JsonObject full = record.deepCopy();
            full.remove("rows");
            full.add("value", after);
            whole.add(full);
        }
        int start = 0;
        while (start < whole.size() && Instant.parse(whole.get(start).get("at").getAsString()).isBefore(cutoff)) start++;
        long size = 0;
        for (int i = whole.size() - 1; i >= start; i--)
        {
            size += recordBytes(records.get(i)) + 1;
            if (size > MAX_BYTES * 3 / 4) { start = i + 1; break; }
        }
        List<JsonObject> kept = new ArrayList<>();
        Set<String> first = new HashSet<>();
        for (int i = start; i < whole.size(); i++)
            kept.add(first.add(whole.get(i).get("section").getAsString()) ? whole.get(i) : records.get(i));
        records.clear();
        records.addAll(kept);
        // Later rows build on what is left, never on a dropped record.
        state.clear();
        for (JsonObject record : records)
        {
            String section = record.get("section").getAsString();
            state.put(section, apply(section, state.get(section), record));
        }
        header.addProperty("droppedOldest", true);
        if (!records.isEmpty()) header.addProperty("startedAt", records.get(0).get("at").getAsString());
        write();
    }

    /** Rewrites the whole file through a temporary file. */
    private void write() throws IOException
    {
        directory.createDirectories();
        StringBuilder text = new StringBuilder(gson.toJson(header)).append('\n');
        for (JsonObject record : records) text.append(gson.toJson(record)).append('\n');
        Filepath path = directory.joinSegment(loadedAccount + ".jsonl");
        Filepath temp = directory.joinSegment(loadedAccount + ".jsonl.tmp");
        byte[] written = text.toString().getBytes(StandardCharsets.UTF_8);
        temp.write(written);
        temp.moveTo(path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        bytes = written.length;
    }

    /** A keyed section's new or changed rows, or null when a row went away (then the whole value is kept). */
    private static JsonArray changedRows(String section, JsonArray before, JsonArray after)
    {
        String key = ChangeAwareEngineBridge.entryKey(section);
        if (key == null || before == null) return null;
        Map<String, JsonElement> old = byKey(before, key);
        Map<String, JsonElement> now = byKey(after, key);
        if (old == null || now == null || !now.keySet().containsAll(old.keySet())) return null;
        JsonArray rows = new JsonArray();
        for (Map.Entry<String, JsonElement> row : now.entrySet())
            if (!row.getValue().equals(old.get(row.getKey()))) rows.add(row.getValue().deepCopy());
        return rows;
    }

    private static JsonArray apply(String section, JsonArray before, JsonObject record)
    {
        if (record.has("value")) return record.getAsJsonArray("value");
        String key = ChangeAwareEngineBridge.entryKey(section);
        Map<String, JsonElement> merged = before == null ? new LinkedHashMap<>() : byKey(before, key);
        if (merged == null) merged = new LinkedHashMap<>();
        for (JsonElement row : record.getAsJsonArray("rows"))
            merged.put(row.getAsJsonObject().get(key).getAsString(), row);
        JsonArray value = new JsonArray();
        for (JsonElement row : merged.values()) value.add(row);
        return value;
    }

    private static Map<String, JsonElement> byKey(JsonArray rows, String key)
    {
        Map<String, JsonElement> map = new LinkedHashMap<>();
        for (JsonElement row : rows)
        {
            if (!row.isJsonObject() || !row.getAsJsonObject().has(key)) return null;
            map.put(row.getAsJsonObject().get(key).getAsString(), row);
        }
        return map;
    }
}
