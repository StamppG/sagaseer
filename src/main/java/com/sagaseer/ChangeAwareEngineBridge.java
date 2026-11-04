package com.sagaseer;

import java.time.Instant;
import java.util.function.BiConsumer;
import net.runelite.client.util.Filepath;
import lombok.extern.slf4j.Slf4j;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import okhttp3.MediaType;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Change-aware uploads: a crash-safe journal keeps each upload's exact bytes until the server's receipt. */
@Slf4j
final class ChangeAwareEngineBridge
{
    static final String REGISTRY_ID = "sagaseer.capture.v1";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final int MAX_DELIVERY_ROUNDS = 4;
    static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024 - 1024;
    private static final long FIRST_RETRY_MS = 3_000L;
    private static final long MAX_RETRY_MS = 15 * 60_000L;
    private static final Map<String, String> SECTIONS = new LinkedHashMap<>();
    static
    {
        SECTIONS.put("state.skills", "skillId");
        SECTIONS.put("state.inventory", null);
        SECTIONS.put("state.equipment", null);
        SECTIONS.put("state.quests", "questId");
        SECTIONS.put("state.questPoints", null);
        SECTIONS.put("state.diaries", "region");
        SECTIONS.put("state.combatAchievements", null);
        SECTIONS.put("state.bosses", "activity");
        SECTIONS.put("state.collectionLog", "page");
        SECTIONS.put("state.logAnnouncements", null);
        SECTIONS.put("state.deaths", null);
        SECTIONS.put("state.killCounts", "varpId");
        SECTIONS.put("state.activities", "key");
        SECTIONS.put("state.account", null);
        SECTIONS.put("state.storage", "location");
        SECTIONS.put("state.gim", null);
        SECTIONS.put("state.clan", null);
        SECTIONS.put("state.slayer", null);
        SECTIONS.put("state.killLog", null);
        SECTIONS.put("state.hunterRumours", null);
        SECTIONS.put("state.raidRewards", null);
        SECTIONS.put("state.timePlayed", null);
        SECTIONS.put("state.farming", null);
        SECTIONS.put("state.travel", null);
        SECTIONS.put("state.music", "track");
        SECTIONS.put("state.itemCharges", null);
        SECTIONS.put("state.stash", null);
        SECTIONS.put("state.stashBoard", null);
        SECTIONS.put("state.grandExchange", null);
        SECTIONS.put("state.house", null);
        SECTIONS.put("state.slayerSuperiors", null);
        SECTIONS.put("state.dropAttempts", null);
        SECTIONS.put("state.consumables", null);
        SECTIONS.put("state.xpHits", null);
        SECTIONS.put("state.grandExchangeHistory", null);
    }
    static String entryKey(String module) { return SECTIONS.get(module); }
    static Set<String> sections() { return SECTIONS.keySet(); }

    /**
     * What the account turned off (from the server's last answer) and what this computer turned off: neither is built, kept or
     * sent.
     */
    private volatile Set<String> serverOff = Set.of();
    /** This computer's switches and their header, swapped together so a reader never sees one new and one old. */
    private static final class LocalOff
    {
        final Set<String> sections;
        final String header;
        LocalOff(Set<String> sections, String header) { this.sections = sections; this.header = header; }
    }
    private volatile LocalOff localOff = new LocalOff(Set.of(), "");
    private volatile String installationLabel;
    private volatile String lastSyncAt;
    private volatile Map<String, String> syncedAt = Map.of();
    /** The last accepted Hunter rumours row, for the sidebar before this session hears one. */
    private volatile JsonArray syncedRumours;
    private volatile String prelinkState;
    private volatile Runnable onStatus = () -> { };
    private volatile Runnable onPolicy = () -> { };
    private PrelinkBuffer prelink;
    private long lastPrelinkReport;
    long prelinkReportMs = 60_000L;

    /**
     * Sections turned off whose queued uploads aren't dropped yet, recorded at the switch so turning one back on first still
     * drops what was read before.
     */
    private final Set<String> turnedOff = new HashSet<>();
    /** Guarded by turnedOff: each switch-off gets the next number, so a reading staged before it can tell. */
    private final Map<String, Long> switchedOffAt = new HashMap<>();
    private long switchNumber;

    /** Under turnedOff's lock. */
    private void noteOff(Set<String> newly)
    {
        turnedOff.addAll(newly);
        switchNumber++;
        for (String section : newly) switchedOffAt.put(section, switchNumber);
    }

    /** The sections switched off after the staging number was taken (even if they are back on now). */
    private Set<String> switchedOffSince(long staged)
    {
        Set<String> since = new HashSet<>();
        synchronized (turnedOff)
        {
            for (Map.Entry<String, Long> section : switchedOffAt.entrySet())
                if (section.getValue() > staged) since.add(section.getKey());
        }
        return since;
    }

    void localOff(List<String> categories)
    {
        Set<String> now = SyncPolicy.sectionsOf(categories);
        synchronized (turnedOff)
        {
            Set<String> newly = new HashSet<>(now);
            newly.removeAll(localOff.sections);
            localOff = new LocalOff(now, String.join(",", categories));
            if (newly.isEmpty()) return;
            noteOff(newly);
        }
        if (io.isShutdown()) return;
        try { io.execute(this::dropOff); }
        catch (RejectedExecutionException stopped) { }
    }

    Set<String> sectionsOff()
    {
        return sectionsOff(localOff);
    }

    private Set<String> sectionsOff(LocalOff local)
    {
        Set<String> off = new HashSet<>(serverOff);
        off.addAll(local.sections);
        return off;
    }

    Set<String> serverOff() { return serverOff; }
    String installationLabel() { return installationLabel; }

    synchronized String boundCharacter() { return characterRecordId; }
    synchronized boolean boundFor(String name, WorldMode.Scope scope)
    {
        return name != null && scope != null && name.equals(account) && scope.equals(boundScope)
            && characterRecordId != null && refusalCode == null;
    }
    String worldRefusal() { return refusalCode; }

    /**
     * The side panel's read for the bound character, on OkHttp's threads; `done` gets the character and the answer (null when
     * unavailable). Nothing is asked unless the bound character is the one shown now. False when nothing was asked.
     */
    boolean readPanel(String currentAccount, WorldMode.Scope scope, BiConsumer<String, PanelRead> done)
    {
        String installation, character, token;
        synchronized (this)
        {
            if (currentAccount == null || scope == null || !currentAccount.equals(account)
                || !scope.equals(boundScope) || refusalCode != null) return false;
            installation = installationId;
            character = characterRecordId;
            token = writeToken;
        }
        if (installation == null || character == null || token == null) return false;
        Request request = new Request.Builder().url(origin + "/v1/installations/" + installation + "/characters/" + character + "/panel")
            .header("Authorization", "Bearer " + token).get().build();
        http.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e) { done.accept(character, null); }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response answer = response)
                {
                    if (!answer.isSuccessful() || answer.body() == null)
                    {
                        reportDenial(installation, character, answer);
                        done.accept(character, null);
                        return;
                    }
                    done.accept(character, PanelRead.parse(new JsonParser().parse(answer.body().string()).getAsJsonObject(), Instant.now()));
                }
                catch (IOException | RuntimeException unreadable) { done.accept(character, null); }
            }
        });
        return true;
    }
    boolean readPanel(String currentAccount, BiConsumer<String, PanelRead> done)
    {
        return readPanel(currentAccount, new WorldMode.Scope(WorldMode.Mode.STANDARD, null), done);
    }
    String lastSyncAt() { return lastSyncAt; }
    Map<String, String> syncedAt() { return syncedAt; }
    JsonArray syncedRumours() { return syncedRumours; }
    String prelinkState() { return prelinkState; }
    void onStatus(Runnable listener) { onStatus = listener; }
    void onPolicy(Runnable listener) { onPolicy = listener; }
    void prelink(PrelinkBuffer buffer) { prelink = buffer; }

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sagaseer-change-aware-local");
        thread.setDaemon(true);
        return thread;
    });
    private final OkHttpClient http;
    private final Gson gson;
    private final Filepath directory;
    private final String origin;
    private final LongSupplier clock;
    private final int uploadBudgetBytes;
    private String account;
    private WorldMode.Scope boundScope;
    private String installationId;
    private String characterRecordId;
    private String writeToken;
    private JsonObject journal;
    private boolean held;
    private long retryDelayMs;
    private long retryAfterMs;
    private long sessionEpoch;
    private String activeAccount;
    private WorldMode.Scope activeScope;
    private String refusedAccount;
    private WorldMode.Scope refusedScope;
    private volatile String refusalCode;
    private volatile long accountHash = -1;

    /** It keeps a character together across renames and installs. */
    void accountHash(long hash) { accountHash = hash; }
    private volatile DenialObserver denialObserver = (installation, status, body) -> { };

    interface DenialObserver
    {
        void onDenied(String installationId, int status, String body);
    }

    void observeDenials(DenialObserver observer) { denialObserver = observer; }

    ChangeAwareEngineBridge(OkHttpClient http, Gson gson, String origin, Filepath directory)
    {
        this(http, gson, origin, directory, System::currentTimeMillis);
    }

    ChangeAwareEngineBridge(OkHttpClient http, Gson gson, String origin, Filepath directory,
                            LongSupplier clock)
    {
        this(http, gson, origin, directory, clock, MAX_UPLOAD_BYTES);
    }

    ChangeAwareEngineBridge(OkHttpClient http, Gson gson, String origin, Filepath directory,
                            LongSupplier clock, int uploadBudgetBytes)
    {
        if (uploadBudgetBytes < 1 || uploadBudgetBytes > MAX_UPLOAD_BYTES)
            throw new IllegalArgumentException("Invalid upload budget");
        URI uri = URI.create(origin);
        boolean loopback = "http".equals(uri.getScheme()) &&
            "127.0.0.1".equals(uri.getHost()) && uri.getPort() > 0;
        if (uploadBudgetBytes != MAX_UPLOAD_BYTES && !loopback)
            throw new IllegalArgumentException("Custom upload budget is for loopback tests only");
        boolean production = "https".equals(uri.getScheme()) &&
            "api.sagaseer.com".equals(uri.getHost()) && uri.getPort() == -1;
        if ((!loopback && !production) || uri.getUserInfo() != null ||
            !"".equals(uri.getPath()) || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Capture origin not approved");
        // Never follow a redirect: the name and account hash headers must reach only the fixed host.
        this.http = http.newBuilder().followRedirects(false).followSslRedirects(false).build();
        this.gson = gson.newBuilder().serializeNulls().create();
        this.origin = origin;
        this.directory = directory;
        this.clock = clock;
        this.uploadBudgetBytes = uploadBudgetBytes;
    }

    synchronized void bind(String account, WorldMode.Scope scope, SagaSeerLink.Credential link, String characterRecordId)
    {
        if (account == null || link == null || characterRecordId == null) return;
        if (!characterRecordId.equals(this.characterRecordId) || !link.installationId.equals(this.installationId))
        {
            journal = null;
            held = false;
            retryDelayMs = 0L;
            retryAfterMs = 0L;
        }
        this.account = account;
        this.boundScope = scope;
        this.installationId = link.installationId;
        this.characterRecordId = characterRecordId;
        this.writeToken = link.token;
    }
    synchronized void bind(String account, SagaSeerLink.Credential link, String characterRecordId)
    {
        bind(account, new WorldMode.Scope(WorldMode.Mode.STANDARD, null), link, characterRecordId);
    }

    void atTrigger(String currentAccount, SagaSeerLink.Credential link,
                   Map<String, ChangeAwareSourceCapture.Observation> observations)
    {
        atTrigger(currentAccount, new WorldMode.Scope(WorldMode.Mode.STANDARD, null), link, observations, false, () -> true);
    }

    /**
     * observations: what was read per module (absent: not read). full: send every observed section whole. The request is
     * journaled, so a restart still sends it.
     */
    void atTrigger(String currentAccount, SagaSeerLink.Credential link,
                   Map<String, ChangeAwareSourceCapture.Observation> observations, boolean full,
                   BooleanSupplier childStillCurrent)
    {
        atTrigger(currentAccount, new WorldMode.Scope(WorldMode.Mode.STANDARD, null), link,
            observations, full, childStillCurrent);
    }

    void atTrigger(String currentAccount, WorldMode.Scope scope, SagaSeerLink.Credential link,
                   Map<String, ChangeAwareSourceCapture.Observation> observations, boolean full,
                   BooleanSupplier childStillCurrent)
    {
        atTrigger(currentAccount, scope, link, observations, full, false, childStillCurrent);
    }

    void atTrigger(String currentAccount, WorldMode.Scope scope, SagaSeerLink.Credential link,
                   Map<String, ChangeAwareSourceCapture.Observation> observations, boolean full,
                   boolean confirmUnchanged, BooleanSupplier childStillCurrent)
    {
        final long ticket;
        final long hash = accountHash;
        synchronized (this)
        {
            if (held || currentAccount == null || scope == null || !scope.mode.recorded() ||
                link == null || childStillCurrent == null ||
                !childStillCurrent.getAsBoolean()) return;
            if (refusedAccount != null && !refusedAccount.equals(currentAccount)) clearWorldRefusal();
            if (activeAccount != null && (!activeAccount.equals(currentAccount) || !scope.equals(activeScope))) sessionEpoch++;
            activeAccount = currentAccount;
            activeScope = scope;
            ticket = sessionEpoch;
        }
        Map<String, ChangeAwareSourceCapture.Observation> reads = new LinkedHashMap<>();
        Map<String, JsonElement> values = new LinkedHashMap<>();
        long staged;
        synchronized (turnedOff) { staged = switchNumber; }
        for (String module : SECTIONS.keySet())
        {
            ChangeAwareSourceCapture.Observation read = observations.get(module);
            if (read == null || sectionsOff().contains(module)) continue;
            reads.put(module, read);
            values.put(module, read.observed() ? read.value.deepCopy() : JsonNull.INSTANCE);
        }
        io.execute(() -> {
            try
            {
                if (!current(ticket, currentAccount, childStillCurrent)) return;
                // A failed binding has no journal yet: wait for the next trigger or heartbeat.
                if (journal == null && clock.getAsLong() < retryAfterMs) return;
                ensureBinding(currentAccount, scope, hash, link, ticket, childStillCurrent);
                if (!current(ticket, currentAccount, childStillCurrent)) return;
                load(characterRecordId, installationId);
                dropOff();
                // Readings staged before a switch went off (even if it is back on now) follow the same rule as the queue.
                Set<String> switched = switchedOffSince(staged);
                if (full) journal.addProperty("fullWanted", true);
                if (confirmUnchanged) journal.addProperty("confirmWanted", true);
                for (Map.Entry<String, ChangeAwareSourceCapture.Observation> item : reads.entrySet())
                {
                    if (sectionsOff().contains(item.getKey()) || switched.contains(item.getKey())) continue;
                    ChangeAwareSourceCapture.Observation read = item.getValue();
                    if (read.mergeOnto != null)
                    {
                        JsonArray merged = read.mergeOnto.apply(latestKnown(item.getKey()));
                        if (merged != null) enqueue(item.getKey(), merged, read.sourceObservedAt, null);
                    }
                    else if (read.observed() || read.failedReadAt != null)
                        enqueue(item.getKey(), values.get(item.getKey()), read.sourceObservedAt,
                            read.failedReadAt);
                }
                scrubDeathItems(journal.getAsJsonArray("queue"), switched);
                save();
                if (clock.getAsLong() < retryAfterMs) return;
                if (current(ticket, currentAccount, childStillCurrent))
                    deliver(ticket, currentAccount, childStillCurrent);
                if (!scope.mode.temporary() && current(ticket, currentAccount, childStillCurrent))
                    prelinkStep(ticket, currentAccount, childStillCurrent);
            }
            catch (IOException | RuntimeException failed)
            {
                // save() sets the hold on a journal write failure; a network failure keeps the exact pending bytes.
                if (!held && refusalCode == null && current(ticket, currentAccount, childStillCurrent))
                    delayRetry();
            }
        });
    }

    synchronized void fenceSession()
    {
        sessionEpoch++;
        activeAccount = null;
        activeScope = null;
    }

    synchronized void clearWorldRefusal()
    {
        refusedAccount = null;
        refusedScope = null;
        refusalCode = null;
    }

    private synchronized boolean current(long ticket, String account,
                                         BooleanSupplier childStillCurrent)
    {
        return ticket == sessionEpoch && account.equals(activeAccount) &&
            childStillCurrent.getAsBoolean();
    }

    private void ensureBinding(String currentAccount, WorldMode.Scope scope, long hash, SagaSeerLink.Credential link,
                               long ticket, BooleanSupplier childStillCurrent) throws IOException
    {
        if (!current(ticket, currentAccount, childStillCurrent)) return;
        if (currentAccount.equals(refusedAccount) && scope.equals(refusedScope))
            throw new IOException("World mode binding refused for this scope");
        if (currentAccount.equals(account) && link.installationId.equals(installationId) &&
            link.token.equals(writeToken) && scope.equals(boundScope) && characterRecordId != null) return;
        String url=origin + "/v1/installations/" + link.installationId + "/capture-character";
        // The account hash goes in a header, never the URL.
        Request.Builder builder=new Request.Builder().url(url).header("Authorization","Bearer " + link.token)
            .header("X-SagaSeer-Local-Off", localOff.header)
            .header("X-SagaSeer-Account", URLEncoder.encode(currentAccount, StandardCharsets.UTF_8))
            .header("X-SagaSeer-World-Mode", scope.mode.name());
        if (scope.mode == WorldMode.Mode.LEAGUES && scope.leagueType != null)
            builder.header("X-SagaSeer-League-Type", scope.leagueType.toString());
        if (hash != -1) builder.header("X-SagaSeer-Account-Hash", Long.toString(hash));
        Request request=builder.get().build();
        try (Response response=http.newCall(request).execute())
        {
            if (!response.isSuccessful() || response.body()==null)
            {
                if (response.code() == 409 && response.body() != null)
                {
                    try
                    {
                        JsonObject refused = new JsonParser().parse(response.body().string()).getAsJsonObject();
                        String code = refused.has("code") ? refused.get("code").getAsString() : "";
                        if ("TEMPORARY_MODE_NOT_SUPPORTED".equals(code) || "SEASON_UNKNOWN".equals(code)
                            || "SEASON_ENDED".equals(code)) rememberRefusal(currentAccount, scope, code);
                    }
                    catch (RuntimeException ignored) { }
                }
                reportDenial(link.installationId, null, response);
                throw new IOException("Character binding unavailable");
            }
            JsonObject resolved=new JsonParser().parse(response.body().string()).getAsJsonObject();
            String echoed = resolved.has("worldMode") && resolved.get("worldMode").isJsonPrimitive()
                ? resolved.get("worldMode").getAsString() : null;
            log.debug("WORLD MODE binding answer mode={} echoed={}", scope.mode, echoed);
            if (echoed == null ? scope.mode != WorldMode.Mode.STANDARD : !scope.mode.name().equals(echoed))
            {
                rememberRefusal(currentAccount, scope, "WORLD_MODE_ECHO_MISMATCH");
                throw new IOException("Character world mode mismatch");
            }
            if (!link.installationId.equals(resolved.get("installationId").getAsString()))
                throw new IOException("Character installation mismatch");
            readPolicy(resolved);
            if (resolved.has("installationLabel") && resolved.get("installationLabel").isJsonPrimitive())
                installationLabel = resolved.get("installationLabel").getAsString();
            if (current(ticket, currentAccount, childStillCurrent))
                bind(currentAccount,scope,link,resolved.get("characterRecordId").getAsString());
        }
    }

    private synchronized void rememberRefusal(String account, WorldMode.Scope scope, String code)
    {
        refusedAccount = account;
        refusedScope = scope;
        refusalCode = code;
        log.debug("WORLD MODE binding refused mode={} code={}", scope.mode, code);
        onStatus.run();
    }

    private Filepath file() { return directory.joinSegment(installationId + "-" + characterRecordId + ".json"); }
    private void load(String character, String installation) throws IOException
    {
        if (journal != null) { fenceWorldMode(); return; }
        directory.createDirectories();
        Filepath path = directory.joinSegment(installation + "-" + character + ".json");
        journal = path.exists() ? new JsonParser().parse(PrelinkBuffer.text(path)).getAsJsonObject()
            : new JsonObject();
        if (!journal.has("schema"))
        {
            journal.addProperty("schema", 3);
            journal.addProperty("installationId", installation);
            journal.addProperty("characterRecordId", character);
            journal.addProperty("ordinal", 0);
            journal.add("accepted", new JsonObject());
            journal.add("queue", new JsonArray());
            journal.add("pending", JsonNull.INSTANCE);
            journal.add("recoveryCutoff", new JsonObject());
        }
        if (journal.get("schema").getAsInt() != 3 ||
            !installation.equals(journal.get("installationId").getAsString()) ||
            !character.equals(journal.get("characterRecordId").getAsString()))
            throw new IOException("Journal scope mismatch");
        fenceWorldMode();
        publishRumours();
    }

    /** Work queued under one world mode never goes out under another: a Hardcore Worlds transfer keeps the character record, and
     * what was waiting from the Hardcore life belongs to the era the transfer froze. */
    private void fenceWorldMode() throws IOException
    {
        WorldMode.Scope scope;
        synchronized (this) { scope = boundScope; }
        if (scope == null) return;
        String mode = scope.mode.name();
        if (journal.has("worldMode") && mode.equals(journal.get("worldMode").getAsString())) return;
        if (journal.has("worldMode"))
        {
            log.debug("WORLD MODE {} -> {}: work queued under the old mode dropped", journal.get("worldMode").getAsString(), mode);
            journal.add("queue", new JsonArray());
            journal.add("pending", JsonNull.INSTANCE);
            journal.remove("fullSources");
            journal.remove("confirmSources");
        }
        journal.addProperty("worldMode", mode);
        save();
    }

    private void publishRumours()
    {
        JsonElement section = journal.getAsJsonObject("accepted").get("state.hunterRumours");
        JsonElement value = section != null && section.isJsonObject() ? section.getAsJsonObject().get("value") : null;
        syncedRumours = value != null && value.isJsonArray() ? value.getAsJsonArray().deepCopy() : null;
    }
    private void save() throws IOException
    {
        Filepath temp = directory.joinSegment(file().getFileName() + ".tmp");
        byte[] bytes = gson.toJson(journal).getBytes(StandardCharsets.UTF_8);
        try
        {
            try (FileChannel channel = temp.openFileChannel(StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
            {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            temp.moveTo(file(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException failed) { held = true; throw failed; }
    }
    private void enqueue(String module, JsonElement value, String time, String failedReadAt)
    {
        JsonObject accepted = journal.getAsJsonObject("accepted");
        JsonObject cutoff = journal.getAsJsonObject("recoveryCutoff");
        if (value.isJsonNull())
        {
            // Missing server state needs a later complete read; a no-read event can't recover it.
            if (cutoff.has(module)) return;
            JsonArray queue = journal.getAsJsonArray("queue");
            for (int i = queue.size() - 1; i >= 0; i--)
            {
                JsonObject prior = queue.get(i).getAsJsonObject();
                if (!module.equals(prior.get("moduleId").getAsString())) continue;
                if (prior.get("value").isJsonNull()) return;
                break;
            }
        }
        JsonObject section = accepted.has(module) && accepted.get(module).isJsonObject()
            ? accepted.getAsJsonObject(module) : null;
        JsonObject observation = new JsonObject();
        observation.addProperty("moduleId", module);
        observation.addProperty("sourceId", UUID.randomUUID().toString());
        observation.addProperty("ordinal", journal.get("ordinal").getAsInt() + 1);
        journal.addProperty("ordinal", observation.get("ordinal").getAsInt());
        observation.addProperty("basisSectionVersion", section == null ? 0 :
            section.get("sectionVersion").getAsInt());
        observation.add("basisStateIdentity",section == null || !section.has("stateIdentity")
            ? JsonNull.INSTANCE : section.get("stateIdentity"));
        String key = SECTIONS.get(module);
        if (key != null && value.isJsonArray())
        {
            List<JsonElement> sorted = new ArrayList<>();
            for (JsonElement row : value.getAsJsonArray()) sorted.add(row);
            sorted.sort((a,b) -> compareKeys(a.getAsJsonObject().getAsJsonPrimitive(key),
                b.getAsJsonObject().getAsJsonPrimitive(key)));
            JsonArray ordered = new JsonArray();
            for (JsonElement row : sorted) ordered.add(row);
            value = ordered;
        }
        observation.add("value", value);
        if (time == null) observation.add("sourceObservedAt", JsonNull.INSTANCE);
        else observation.addProperty("sourceObservedAt", time);
        if (failedReadAt == null) observation.add("failedReadAt", JsonNull.INSTANCE);
        else observation.addProperty("failedReadAt", failedReadAt);
        JsonObject refused = refusedValues();
        if (refused.has(module))
        {
            if (refused.get(module).getAsString().equals(valueDigest(value))) return;
            refused.remove(module);
        }
        journal.getAsJsonArray("queue").add(observation);
        capQueue(module);
    }

    /** Waiting copies kept per section while uploads can't go out (an outage): enough for the order of real changes. */
    static final int QUEUE_PER_SECTION = 20;

    /**
     * Past the cap, the oldest waiting copies of this section go, never one an unsent attempt holds or a full resend or
     * confirmation has claimed (at most a few per section), so the newest value survives and an exact retry stays exact.
     */
    private void capQueue(String module)
    {
        JsonArray queue = journal.getAsJsonArray("queue");
        Set<String> claimed = new HashSet<>();
        JsonElement pending = journal.get("pending");
        if (pending != null && pending.isJsonObject())
            for (JsonElement entry : pending.getAsJsonObject().getAsJsonArray("entries"))
                if (entry.getAsJsonObject().has("sourceId")) claimed.add(entry.getAsJsonObject().get("sourceId").getAsString());
        for (String field : new String[] {"fullSources", "confirmSources"})
            if (journal.has(field)) for (Map.Entry<String, JsonElement> source : journal.getAsJsonObject(field).entrySet())
                claimed.add(source.getValue().getAsString());
        int waiting = 0;
        for (JsonElement item : queue) if (module.equals(item.getAsJsonObject().get("moduleId").getAsString())) waiting++;
        for (int i = 0; i < queue.size() && waiting > QUEUE_PER_SECTION;)
        {
            JsonObject observation = queue.get(i).getAsJsonObject();
            if (module.equals(observation.get("moduleId").getAsString()) && !claimed.contains(observation.get("sourceId").getAsString()))
            {
                queue.remove(i);
                waiting--;
            }
            else i++;
        }
    }
    private JsonObject refusedValues()
    {
        if (!journal.has("refusedValues")) journal.add("refusedValues", new JsonObject());
        return journal.getAsJsonObject("refusedValues");
    }
    private String valueDigest(JsonElement value)
    {
        try
        {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                gson.toJson(value).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(bytes);
        }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private JsonArray latestKnown(String module)
    {
        JsonArray queue = journal.getAsJsonArray("queue");
        for (int i = queue.size() - 1; i >= 0; i--)
        {
            JsonObject item = queue.get(i).getAsJsonObject();
            if (module.equals(item.get("moduleId").getAsString()) && item.get("value").isJsonArray())
                return item.getAsJsonArray("value").deepCopy();
        }
        JsonElement section = journal.getAsJsonObject("accepted").get(module);
        if (section != null && section.isJsonObject() && section.getAsJsonObject().get("value").isJsonArray())
            return section.getAsJsonObject().getAsJsonArray("value").deepCopy();
        return null;
    }

    private static int compareKeys(JsonPrimitive a, JsonPrimitive b)
    {
        return a.isNumber() ? Double.compare(a.getAsDouble(), b.getAsDouble())
            : a.getAsString().compareTo(b.getAsString());
    }
    private String endpoint() { return origin + "/v1/installations/" + installationId +
        "/characters/" + characterRecordId; }
    private JsonObject get(String path, long ticket, String currentAccount,
                           BooleanSupplier childStillCurrent) throws IOException
    {
        if (!current(ticket, currentAccount, childStillCurrent))
            throw new IOException("Capture session changed");
        String forCharacter = characterRecordId;
        Request request = new Request.Builder().url(endpoint() + path)
            .header("Authorization", "Bearer " + writeToken)
            .get().build();
        try (Response response = http.newCall(request).execute())
        {
            if (!response.isSuccessful())
            {
                reportDenial(installationId, forCharacter, response);
                throw new IOException("Readback unavailable");
            }
            if (response.body() == null || !current(ticket, currentAccount, childStillCurrent))
                throw new IOException("Readback unavailable");
            return new JsonParser().parse(response.body().string()).getAsJsonObject();
        }
    }
    /** `character`: the one the failed request named (null: none yet, as when binding). */
    private void reportDenial(String installation, String character, Response response) throws IOException
    {
        if (response.code() == 404)
        {
            forgetDeletedCharacter(installation, character, response);
            return;
        }
        if (response.code() != 401 && response.code() != 403) return;
        String body = response.body() == null ? null : response.body().string();
        denialObserver.onDenied(installation, response.code(), body);
    }
    /** The bound character was deleted on the website: forget it and its saved queue (never sendable now), so the next trigger binds
     * a fresh character, as a first link does. Called from the upload thread and from the panel read's callback, so the forgetting
     * itself runs on the upload thread, and only if that character is still the bound one. */
    private void forgetDeletedCharacter(String installation, String deleted, Response response) throws IOException
    {
        if (installation == null || deleted == null) return;
        String body = response.body() == null ? "" : response.body().string();
        if (!body.contains("\"CHARACTER_NOT_FOUND\"") || io.isShutdown()) return;
        try { io.execute(() ->
        {
            Filepath old;
            synchronized (this)
            {
                // A late answer about a character no longer bound (a newer one bound meanwhile) changes nothing.
                if (!deleted.equals(characterRecordId) || !installation.equals(installationId)) return;
                log.debug("SagaSeer character deleted on the website; binding again at the next trigger");
                old = file();
                characterRecordId = null;
                journal = null;
                held = false;
                syncedRumours = null;
            }
            try { old.deleteIfExists(); }
            catch (IOException unwritable) { log.debug("SagaSeer could not remove a deleted character's journal"); }
        }); }
        catch (RejectedExecutionException stopped) { /* The plugin stopped meanwhile: nothing to forget. */ }
    }
    /** Uploads until the queue is empty, bounded so a server that keeps answering with conflicts can't loop forever. */
    private void deliver(long ticket, String currentAccount,
                         BooleanSupplier childStillCurrent) throws IOException
    {
        for (int round = 0; round < MAX_DELIVERY_ROUNDS; round++)
        {
            if (!deliverOne(ticket, currentAccount, childStillCurrent) ||
                !current(ticket, currentAccount, childStillCurrent)) return;
            clearRetryDelay();
        }
    }
    private void delayRetry()
    {
        retryDelayMs = retryDelayMs == 0L ? FIRST_RETRY_MS : Math.min(MAX_RETRY_MS, retryDelayMs * 2);
        retryAfterMs = clock.getAsLong() + retryDelayMs;
        log.debug("SagaSeer capture retry deferred for {} ms", retryDelayMs);
    }
    private void clearRetryDelay()
    {
        retryDelayMs = 0L;
        retryAfterMs = 0L;
    }
    private boolean deliverOne(long ticket, String currentAccount,
                               BooleanSupplier childStillCurrent) throws IOException
    {
        if (journal.get("pending").isJsonNull()) prepare();
        if (journal.get("pending").isJsonNull()) return false;
        JsonObject pending = journal.getAsJsonObject("pending");
        String operationId = pending.get("operationId").getAsString();
        String path = "/capture-attempts/" + operationId;
        JsonObject receipt = get(path, ticket, currentAccount, childStillCurrent);
        if ("ABSENT".equals(receipt.get("state").getAsString()))
        {
            if (!current(ticket, currentAccount, childStillCurrent)) return false;
            dropOff();
            // Never sent, and it holds a data type turned off since: made again without it.
            if (pending.has("dropped") || holdsOff(pending, sectionsOff()))
            {
                journal.add("pending", JsonNull.INSTANCE);
                save();
                return true;
            }
            byte[] exact = Base64.getDecoder().decode(pending.get("bytesBase64").getAsString());
            // A journal from before byte-aware splitting may hold an oversized request the server never saw: rebuild it under the
            // size rules.
            if (exact.length > uploadBudgetBytes)
            {
                restorePendingIntent(pending);
                journal.add("pending", JsonNull.INSTANCE);
                save();
                return true;
            }
            // Every upload names its world, so the server refuses a temporary world's upload even if a binding were wrong.
            WorldMode.Scope world;
            synchronized (this) { world = activeScope; }
            String forCharacter = characterRecordId;
            Request request = new Request.Builder().url(endpoint() + path)
                .header("Authorization", "Bearer " + writeToken)
                .header("X-SagaSeer-Local-Off", localOff.header)
                .header("X-SagaSeer-World-Mode", world == null ? WorldMode.Mode.STANDARD.name() : world.mode.name())
                .post(RequestBody.create(JSON, exact)).build();
            try (Response response = http.newCall(request).execute())
            {
                if (!response.isSuccessful() || response.body() == null)
                {
                    reportDenial(installationId, forCharacter, response);
                    throw new IOException("Attempt unavailable");
                }
                receipt = new JsonParser().parse(response.body().string()).getAsJsonObject();
            }
        }
        if (!"COMMITTED".equals(receipt.get("state").getAsString())) return false;
        settle(receipt);
        return true;
    }
    private void restorePendingIntent(JsonObject pending)
    {
        JsonArray queue = journal.getAsJsonArray("queue");
        for (JsonElement item : pending.getAsJsonArray("entries"))
        {
            JsonObject entry = item.getAsJsonObject();
            String operation = entry.get("operation").getAsString();
            String field = "FULL".equals(operation) ? "fullSources" :
                "CONFIRM".equals(operation) ? "confirmSources" : null;
            if (field == null) continue;
            String module = entry.get("moduleId").getAsString();
            String sourceId = entry.get("sourceId").getAsString();
            if (!queuedSource(queue, module, sourceId)) continue;
            JsonObject sources = journal.has(field) ? journal.getAsJsonObject(field) : new JsonObject();
            sources.addProperty(module, sourceId);
            journal.add(field, sources);
        }
    }
    private void prepare() throws IOException
    {
        JsonArray queue = journal.getAsJsonArray("queue");
        JsonObject accepted = journal.getAsJsonObject("accepted");
        JsonObject cutoff = journal.getAsJsonObject("recoveryCutoff");
        JsonArray entries = new JsonArray();
        JsonArray kept = new JsonArray();
        for (JsonElement item : queue)
            if (!sectionsOff().contains(item.getAsJsonObject().get("moduleId").getAsString())) kept.add(item);
        if (kept.size() != queue.size()) { journal.add("queue", kept); queue = kept; }
        scrubDeathItems(queue, sectionsOff());
        JsonObject fullSources = batchSources("fullWanted", "fullSources", queue, cutoff);
        JsonObject confirmSources = batchSources("confirmWanted", "confirmSources", queue, cutoff);
        dropUnchangedLeading(queue, accepted, cutoff, confirmSources);
        String operationId = UUID.randomUUID().toString();
        JsonObject message = new JsonObject();
        message.addProperty("schema", 3);
        message.addProperty("registryId", REGISTRY_ID);
        message.addProperty("operationId", operationId);
        message.addProperty("installationId", installationId);
        message.addProperty("characterRecordId", characterRecordId);
        message.add("entries", entries);
        for (Map.Entry<String, String> definition : SECTIONS.entrySet())
        {
            String module = definition.getKey();
            String key = definition.getValue();
            JsonObject observation = null;
            for (JsonElement item : queue)
                if (module.equals(item.getAsJsonObject().get("moduleId").getAsString()) &&
                    !(cutoff.has(module) && item.getAsJsonObject().get("value").isJsonNull()))
                { observation = item.getAsJsonObject(); break; }
            if (observation == null) continue;
            String sourceId = observation.get("sourceId").getAsString();
            boolean full = fullSources.has(module) && sourceId.equals(fullSources.get(module).getAsString());
            boolean confirm = confirmSources.has(module) && sourceId.equals(confirmSources.get(module).getAsString());
            JsonObject section = accepted.has(module) && accepted.get(module).isJsonObject()
                ? accepted.getAsJsonObject(module) : null;
            JsonObject entry = new JsonObject();
            entry.addProperty("moduleId", module);
            entry.addProperty("codecVersion", 1);
            entry.add("sourceId", observation.get("sourceId"));
            JsonElement value = observation.get("value");
            if (value.isJsonNull())
            {
                if (cutoff.has(module)) continue;
                entry.addProperty("operation", "UNKNOWN");
                if (!observation.has("failedReadAt") || observation.get("failedReadAt").isJsonNull())
                    continue;
                entry.add("failedReadAt",observation.get("failedReadAt"));
                entry.addProperty("reason", "SOURCE_UNAVAILABLE");
            }
            else if (confirm && section != null && value.equals(section.get("value")))
            {
                entry.addProperty("operation", "CONFIRM");
                entry.add("sourceObservedAt", observation.get("sourceObservedAt"));
                entry.addProperty("baseSectionVersion", section.get("sectionVersion").getAsInt());
                entry.add("baseValueDigest", section.get("valueDigest"));
            }
            else if (full)
            {
                entry.addProperty("operation", "FULL");
                entry.add("sourceObservedAt", observation.get("sourceObservedAt"));
                entry.add("value", value);
            }
            else if (cutoff.has(module) &&
                observation.get("ordinal").getAsInt() <= cutoff.get(module).getAsInt())
            {
                entry.addProperty("operation", "HISTORY_ONLY");
                entry.add("sourceObservedAt", observation.get("sourceObservedAt"));
                entry.add("value", value);
            }
            else if (section == null)
            {
                entry.addProperty("operation", "BASELINE");
                entry.add("sourceObservedAt", observation.get("sourceObservedAt"));
                entry.add("value", value);
            }
            else
            {
                entry.add("sourceObservedAt", observation.get("sourceObservedAt"));
                entry.addProperty("baseSectionVersion",section.get("sectionVersion").getAsInt());
                entry.add("baseValueDigest",section.get("valueDigest"));
                boolean equal = value.equals(section.get("value"));
                if (!equal && observation.get("sourceObservedAt").getAsString()
                    .compareTo(section.get("lastSourceObservedAt").getAsString()) <= 0)
                {
                    entry.addProperty("operation", "HISTORY_ONLY");
                    entry.remove("baseSectionVersion");
                    entry.remove("baseValueDigest");
                    entry.add("value",value);
                }
                else if (equal)
                    entry.addProperty("operation", "CONFIRM");
                else if (key == null)
                { entry.addProperty("operation", "REPLACE"); entry.add("value",value); }
                else
                {
                    Map<String,JsonElement> old = new TreeMap<>();
                    for (JsonElement row : section.getAsJsonArray("value"))
                        old.put(row.getAsJsonObject().get(key).getAsString(),row);
                    Set<String> keys = new HashSet<>();
                    JsonArray changes = new JsonArray();
                    for (JsonElement row : value.getAsJsonArray())
                    {
                        String id = row.getAsJsonObject().get(key).getAsString();
                        keys.add(id);
                        if (!row.equals(old.get(id))) changes.add(row);
                    }
                    if (keys.equals(old.keySet()))
                    {
                        entry.addProperty("operation", "ENTRY_UPDATES");
                        entry.add("entries",changes);
                    }
                    else
                    {
                        // ENTRY_UPDATES cannot add or drop keys, so send the section whole.
                        entry.addProperty("operation", "FULL");
                        entry.remove("baseSectionVersion");
                        entry.remove("baseValueDigest");
                        entry.add("value",value);
                    }
                }
            }
            entries.add(entry);
            // Measure the real UTF-8 body. A section that can't fit alone is refused locally, so it never stalls its siblings.
            if (gson.toJson(message).getBytes(StandardCharsets.UTF_8).length > uploadBudgetBytes)
            {
                entries.remove(entries.size() - 1);
                if (entries.size() > 0) break;
                refuseTooLarge(module, observation, queue, fullSources, confirmSources);
            }
        }
        if (entries.size() == 0) { save(); return; }
        byte[] bytes = gson.toJson(message).getBytes(StandardCharsets.UTF_8);
        JsonObject pending = new JsonObject();
        pending.addProperty("operationId",operationId);
        pending.addProperty("bytesBase64",Base64.getEncoder().encodeToString(bytes));
        pending.add("entries",entries);
        journal.add("pending",pending);
        save();
    }
    private void refuseTooLarge(String module, JsonObject observation, JsonArray queue,
                                JsonObject fullSources, JsonObject confirmSources)
    {
        JsonElement value = observation.get("value");
        refusedValues().addProperty(module, valueDigest(value));
        for (int i = queue.size() - 1; i >= 0; i--)
        {
            JsonObject row = queue.get(i).getAsJsonObject();
            if (module.equals(row.get("moduleId").getAsString()) && value.equals(row.get("value")))
                queue.remove(i);
        }
        String sourceId = observation.get("sourceId").getAsString();
        if (fullSources.has(module) && sourceId.equals(fullSources.get(module).getAsString()))
            fullSources.remove(module);
        if (confirmSources.has(module) && sourceId.equals(confirmSources.get(module).getAsString()))
            confirmSources.remove(module);
        log.warn("SagaSeer upload section refused module={} code=TOO_LARGE", module);
    }
    /**
     * Freeze the first queued observation of each section a FULL or heartbeat covers, so split uploads and restarts keep that
     * intent and later observations aren't promoted.
     */
    private JsonObject batchSources(String wanted, String field, JsonArray queue, JsonObject cutoff)
    {
        JsonObject sources = journal.has(field) ? journal.getAsJsonObject(field) : new JsonObject();
        for (String module : new HashSet<>(sources.keySet()))
            if (!queuedSource(queue, module, sources.get(module).getAsString())) sources.remove(module);
        if (sources.size() == 0 && journal.has(wanted) && journal.get(wanted).getAsBoolean())
        {
            for (JsonElement item : queue)
            {
                JsonObject observation = item.getAsJsonObject();
                String module = observation.get("moduleId").getAsString();
                if (SECTIONS.containsKey(module) && !sources.has(module) &&
                    !(cutoff.has(module) && observation.get("value").isJsonNull()))
                    sources.add(module, observation.get("sourceId"));
            }
            if (sources.size() > 0) journal.remove(wanted);
        }
        journal.add(field, sources);
        return sources;
    }
    private static boolean queuedSource(JsonArray queue, String module, String sourceId)
    {
        for (JsonElement item : queue)
        {
            JsonObject observation = item.getAsJsonObject();
            if (module.equals(observation.get("moduleId").getAsString()) &&
                sourceId.equals(observation.get("sourceId").getAsString())) return true;
        }
        return false;
    }
    /**
     * Ordinary triggers drop only unchanged observations at the head of each queue; a change and a return to the old value still
     * go in order.
     */
    private void dropUnchangedLeading(JsonArray queue, JsonObject accepted, JsonObject cutoff,
                                      JsonObject confirmSources)
    {
        for (String module : SECTIONS.keySet())
        {
            JsonObject section = accepted.has(module) && accepted.get(module).isJsonObject()
                ? accepted.getAsJsonObject(module) : null;
            if (section == null || !section.has("value")) continue;
            for (int i = 0; i < queue.size();)
            {
                JsonObject observation = queue.get(i).getAsJsonObject();
                if (!module.equals(observation.get("moduleId").getAsString()) ||
                    cutoff.has(module) && observation.get("value").isJsonNull())
                { i++; continue; }
                if (confirmSources.has(module) && observation.get("sourceId").equals(confirmSources.get(module))) break;
                if (!observation.get("value").equals(section.get("value"))) break;
                queue.remove(i);
            }
        }
    }
    private void settle(JsonObject receipt) throws IOException
    {
        JsonObject pending = journal.getAsJsonObject("pending");
        if (!pending.get("operationId").getAsString().equals(receipt.get("operationId").getAsString()) ||
            !installationId.equals(receipt.get("installationId").getAsString()) ||
            !characterRecordId.equals(receipt.get("characterRecordId").getAsString()))
            throw new IOException("Receipt mismatch");
        JsonObject statuses = new JsonObject();
        JsonObject codes = new JsonObject();
        for (JsonElement row : receipt.getAsJsonArray("sections"))
        {
            JsonObject item = row.getAsJsonObject();
            String module = item.get("moduleId").getAsString();
            String status = item.get("status").getAsString();
            statuses.addProperty(module, status);
            if ("REFUSED".equals(status))
            {
                if (!item.has("code") || !item.get("code").isJsonPrimitive())
                    throw new IOException("Refusal code missing");
                codes.add(module, item.get("code"));
            }
        }
        JsonObject sent = new JsonObject();
        for (JsonElement row : pending.getAsJsonArray("entries"))
            sent.addProperty(row.getAsJsonObject().get("sourceId").getAsString(),
                row.getAsJsonObject().get("moduleId").getAsString());
        JsonArray remaining = new JsonArray();
        JsonObject accepted = journal.getAsJsonObject("accepted");
        JsonObject cutoff = journal.getAsJsonObject("recoveryCutoff");
        JsonObject refused = refusedValues();
        Map<String, String> newlyRefused = new LinkedHashMap<>();
        for (JsonElement row : journal.getAsJsonArray("queue"))
        {
            JsonObject observation = row.getAsJsonObject();
            String module = observation.get("moduleId").getAsString();
            if (sent.has(observation.get("sourceId").getAsString()) &&
                "REFUSED".equals(statuses.get(module).getAsString()))
            {
                newlyRefused.put(module, valueDigest(observation.get("value")));
                log.warn("SagaSeer upload section refused module={} code={}", module,
                    codes.get(module).getAsString());
            }
        }
        // A section the server no longer holds (a Hardcore Worlds reset drops the old life's) is forgotten here too, so its next
        // read goes in whole instead of being matched against the old value.
        Set<String> sentModules = new HashSet<>();
        for (Map.Entry<String, JsonElement> item : sent.entrySet()) sentModules.add(item.getValue().getAsString());
        if (receipt.has("acceptedSections") && receipt.get("acceptedSections").isJsonObject())
            for (Map.Entry<String, JsonElement> item : receipt.getAsJsonObject("acceptedSections").entrySet())
                if (item.getValue().isJsonNull() && !sentModules.contains(item.getKey()) && accepted.has(item.getKey())
                    && accepted.get(item.getKey()).isJsonObject())
                    accepted.add(item.getKey(), JsonNull.INSTANCE);
        for (Map.Entry<String, String> item : newlyRefused.entrySet())
            refused.addProperty(item.getKey(), item.getValue());
        for (JsonElement row : journal.getAsJsonArray("queue"))
        {
            JsonObject observation = row.getAsJsonObject();
            String module = observation.get("moduleId").getAsString();
            if (newlyRefused.containsKey(module) && newlyRefused.get(module).equals(valueDigest(observation.get("value"))))
                continue;
            if (!sent.has(observation.get("sourceId").getAsString()))
            { remaining.add(observation); continue; }
            String status = statuses.get(module).getAsString();
            if ("RECOVERY_REQUIRED".equals(status))
            {
                accepted.add(module,JsonNull.INSTANCE);
                cutoff.addProperty(module,journal.get("ordinal").getAsInt());
                remaining.add(observation);
            }
            else
            {
                accepted.add(module,receipt.getAsJsonObject("acceptedSections").get(module));
                if ("HEAD_CONFLICT".equals(status) && !observation.get("value").isJsonNull())
                    remaining.add(observation);
                if ("ACCEPTED".equals(status)) cutoff.remove(module);
            }
        }
        for (Map.Entry<String, JsonElement> item : statuses.entrySet())
            if ("ACCEPTED".equals(item.getValue().getAsString())) refused.remove(item.getKey());
        JsonArray recoverable = new JsonArray();
        for (JsonElement row : remaining)
        {
            JsonObject observation = row.getAsJsonObject();
            String module = observation.get("moduleId").getAsString();
            if (cutoff.has(module) && observation.get("value").isJsonNull()) continue;
            recoverable.add(observation);
        }
        journal.add("queue",recoverable);
        journal.add("pending",JsonNull.INSTANCE);
        for (String field : List.of("fullSources", "confirmSources"))
            if (journal.has(field))
            {
                JsonObject sources = journal.getAsJsonObject(field);
                for (String module : new HashSet<>(sources.keySet()))
                    if (sent.has(sources.get(module).getAsString())) sources.remove(module);
            }
        save();
        readPolicy(receipt);
        Map<String, String> times = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> section : accepted.entrySet())
            if (section.getValue().isJsonObject() && section.getValue().getAsJsonObject().has("lastSourceObservedAt")
                && section.getValue().getAsJsonObject().get("lastSourceObservedAt").isJsonPrimitive())
                times.put(section.getKey(), section.getValue().getAsJsonObject().get("lastSourceObservedAt").getAsString());
        syncedAt = times;
        publishRumours();
        lastSyncAt = receipt.has("acceptedAt") && receipt.get("acceptedAt").isJsonPrimitive()
            ? receipt.get("acceptedAt").getAsString() : ChangeAwareSourceCapture.time(Instant.now());
        onStatus.run();
    }

    /**
     * The account's switches from a binding answer or receipt (an older server sends none). A newly off type drops its queue and
     * tells the plugin.
     */
    private void readPolicy(JsonObject answer)
    {
        if (!answer.has("sectionsOff") || !answer.get("sectionsOff").isJsonArray()) return;
        Set<String> off = new HashSet<>();
        for (JsonElement id : answer.getAsJsonArray("sectionsOff")) off.add(id.getAsString());
        Set<String> newly = new HashSet<>(off);
        newly.removeAll(serverOff);
        boolean changed = !off.equals(serverOff);
        serverOff = Set.copyOf(off);
        if (!newly.isEmpty())
        {
            synchronized (turnedOff) { noteOff(newly); }
            dropOff();
        }
        // Any change, a type turned back on too, refreshes what the plugin reads.
        if (changed) onPolicy.run();
    }

    private static boolean holdsOff(JsonObject pending, Set<String> off)
    {
        for (JsonElement entry : pending.getAsJsonArray("entries"))
        {
            String module = entry.getAsJsonObject().get("moduleId").getAsString();
            if (off.contains(module)) return true;
            if (DEATHS.equals(module) && itemsOff(off)
                && (carriesItems(entry.getAsJsonObject().get("value")) || carriesItems(entry.getAsJsonObject().get("entries"))))
                return true;
        }
        return false;
    }

    private static final String DEATHS = "state.deaths";

    /** A death record holds the items lost and kept, so it goes without them while either item type is off. */
    private static boolean itemsOff(Set<String> off) { return off.contains("state.inventory") || off.contains("state.equipment"); }

    private static boolean carriesItems(JsonElement rows)
    {
        if (rows == null || !rows.isJsonArray()) return false;
        for (JsonElement row : rows.getAsJsonArray())
            if (row.isJsonObject() && row.getAsJsonObject().has("items") && !row.getAsJsonObject().get("items").isJsonNull()) return true;
        return false;
    }

    /** Waiting death records lose their items while an item type is off; true when one changed. */
    private static boolean scrubDeathItems(JsonArray queue, Set<String> off)
    {
        if (!itemsOff(off)) return false;
        boolean changed = false;
        for (JsonElement item : queue)
        {
            JsonObject observation = item.getAsJsonObject();
            if (!DEATHS.equals(observation.get("moduleId").getAsString()) || !carriesItems(observation.get("value"))) continue;
            for (JsonElement row : observation.getAsJsonArray("value"))
                if (row.isJsonObject() && row.getAsJsonObject().has("items")) row.getAsJsonObject().add("items", JsonNull.INSTANCE);
            changed = true;
        }
        return changed;
    }

    /**
     * IO thread. A type turned off loses its queued uploads at once, and an unsent attempt holding it is remade without it. Pause
     * keeps the queue.
     */
    private void dropOff()
    {
        if (journal == null) return;
        Set<String> off;
        synchronized (turnedOff)
        {
            off = new HashSet<>(turnedOff);
            turnedOff.clear();
        }
        off.addAll(sectionsOff());
        JsonArray queue = journal.getAsJsonArray("queue");
        JsonArray kept = new JsonArray();
        for (JsonElement item : queue)
            if (!off.contains(item.getAsJsonObject().get("moduleId").getAsString())) kept.add(item);
        boolean changed = kept.size() != queue.size();
        JsonElement pending = journal.get("pending");
        if (pending.isJsonObject() && !pending.getAsJsonObject().has("dropped") && holdsOff(pending.getAsJsonObject(), off))
        {
            pending.getAsJsonObject().addProperty("dropped", true);
            changed = true;
        }
        // After the attempt check: an unsent attempt shares its values with the queue, so scrubbing first would hide its items.
        if (scrubDeathItems(kept, off)) changed = true;
        if (!changed) return;
        journal.add("queue", kept);
        try { save(); }
        catch (IOException failed) { }
    }

    /**
     * Pre-link history: report what is kept (at most once a minute), then import it in batches or start fresh, as the player
     * chose. The local file goes once the server has it.
     */
    private void prelinkStep(long ticket, String currentAccount, BooleanSupplier childStillCurrent) throws IOException
    {
        PrelinkBuffer buffer = prelink;
        if (buffer == null) return;
        // A data type that is off is neither offered nor imported; each request filters and labels with one snapshot of the
        // switches.
        LocalOff local = localOff;
        PrelinkBuffer.Summary summary = buffer.summary(currentAccount, sectionsOff(local));
        if (summary == null) return;
        long now = System.currentTimeMillis();
        if (now - lastPrelinkReport < prelinkReportMs) return;
        lastPrelinkReport = now;
        JsonObject report = new JsonObject();
        report.add("summary", summary.toJson());
        JsonObject answer = post("/prelink", report, local, ticket, currentAccount, childStillCurrent);
        String choice = answer.has("choice") && answer.get("choice").isJsonPrimitive() ? answer.get("choice").getAsString() : null;
        if (choice == null) { prelinkState = "WAITING"; onStatus.run(); return; }
        boolean finished = answer.has("finished") && answer.get("finished").getAsBoolean();
        if ("IMPORT".equals(choice) && !finished)
            for (JsonArray batch : buffer.batches(currentAccount, PRELINK_BATCH, sectionsOff(local),
                section -> log.warn("SagaSeer pre-link record refused module={} code=TOO_LARGE", section)))
            {
                LocalOff switches = localOff;
                Set<String> off = sectionsOff(switches);
                JsonArray kept = new JsonArray();
                for (JsonElement record : batch)
                    if (!off.contains(record.getAsJsonObject().get("section").getAsString())) kept.add(record);
                if (kept.size() == 0) continue;
                JsonObject records = new JsonObject();
                records.add("records", kept);
                post("/prelink/records", records, switches, ticket, currentAccount, childStillCurrent);
            }
        JsonObject done = new JsonObject();
        done.addProperty("finished", true);
        post("/prelink", done, localOff, ticket, currentAccount, childStillCurrent);
        buffer.clear(currentAccount);
        prelinkState = "IMPORT".equals(choice) ? "IMPORTED" : "FRESH";
        onStatus.run();
    }
    static final int PRELINK_BATCH = 50;

    private JsonObject post(String path, JsonObject body, LocalOff local, long ticket, String currentAccount,
                            BooleanSupplier childStillCurrent) throws IOException
    {
        if (!current(ticket, currentAccount, childStillCurrent)) throw new IOException("Capture session changed");
        // Send the switches this request was filtered with, so records of a type just turned back on aren't dropped.
        Request request = new Request.Builder().url(endpoint() + path)
            .header("Authorization", "Bearer " + writeToken)
            .header("X-SagaSeer-Local-Off", local.header)
            .post(RequestBody.create(JSON, gson.toJson(body).getBytes(StandardCharsets.UTF_8))).build();
        try (Response response = http.newCall(request).execute())
        {
            if (!response.isSuccessful() || response.body() == null)
            {
                reportDenial(installationId, characterRecordId, response);
                throw new IOException("Pre-link request unavailable");
            }
            return new JsonParser().parse(response.body().string()).getAsJsonObject();
        }
    }
    void shutdown() { io.shutdownNow(); }
    void awaitIdleForTest() throws Exception { io.submit(() -> { }).get(10, TimeUnit.SECONDS); }
}
