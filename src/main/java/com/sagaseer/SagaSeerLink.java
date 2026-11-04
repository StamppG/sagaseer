package com.sagaseer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.runelite.client.util.Filepath;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * This installation's link to a SagaSeer account. The plugin makes a secret verifier and sends only its hash; once the owner
 * approves in the browser, the verifier is the token. Saved once, kept across restarts and characters.
 */
final class SagaSeerLink
{
    static final class Credential
    {
        final String installationId;
        final String token;

        Credential(String installationId, String token)
        {
            this.installationId = installationId;
            this.token = token;
        }
    }

    static final class Pending
    {
        final String requestId;
        final String displayCode;
        final String browserUrl;
        final Instant expiresAt;

        Pending(String requestId, String displayCode, String browserUrl, Instant expiresAt)
        {
            this.requestId = requestId;
            this.displayCode = displayCode;
            this.browserUrl = browserUrl;
            this.expiresAt = expiresAt;
        }
    }

    enum PollResult { WAITING, LINKED, EXPIRED, FAILED }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Pattern ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern CODE = Pattern.compile("[0-9]{6}");
    private static final String FILE = "sagaseer-link.json";
    private static final int MAX_BYTES = 4096;

    private final OkHttpClient http;
    private final Gson gson;
    private final Filepath file;
    private final String platformOrigin;
    private final String websiteOrigin;
    private final SecureRandom random = new SecureRandom();
    private volatile Credential credential;
    /** Written under the lock, read without it: the sidebar never waits on a link request. */
    private volatile Pending pending;
    private String verifier;

    private SagaSeerLink(OkHttpClient http, Gson gson, Filepath directory,
            String platformOrigin, String websiteOrigin)
    {
        this.http = http.newBuilder().followRedirects(false).followSslRedirects(false)
                .callTimeout(10, TimeUnit.SECONDS).build();
        this.gson = gson;
        this.file = directory.join(FILE);
        this.platformOrigin = platformOrigin;
        this.websiteOrigin = websiteOrigin;
    }

    /** Opens the saved link; an unreadable or foreign file is not linked. */
    static SagaSeerLink open(OkHttpClient http, Gson gson, Filepath directory,
            String platformOrigin, String websiteOrigin) throws IOException
    {
        directory.createDirectories();
        SagaSeerLink link = new SagaSeerLink(http, gson, directory, platformOrigin, websiteOrigin);
        link.credential = link.read();
        return link;
    }

    Credential credential() { return credential; }

    Pending pending() { return pending; }

    /** Starts a link request, approved at the returned URL. */
    synchronized Pending start() throws IOException
    {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String fresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        JsonObject body = new JsonObject();
        body.addProperty("label", "RuneLite on this device");
        body.addProperty("verifierHash", sha256Hex(fresh));
        JsonObject reply = post("/v1/installation-links/requests", body, 201);
        String requestId = reply.get("requestId").getAsString();
        String code = reply.get("displayCode").getAsString();
        String url = reply.get("browserUrl").getAsString();
        Instant expires = Instant.parse(reply.get("expiresAt").getAsString());
        if (!ID.matcher(requestId).matches() || !CODE.matcher(code).matches() ||
                !url.equals(websiteOrigin + "/link?requestId=" + requestId))
            throw new IOException("Unexpected link response");
        verifier = fresh;
        pending = new Pending(requestId, code, url, expires);
        return pending;
    }

    /** Asks whether the owner approved; saves the link when so. */
    synchronized PollResult poll() throws IOException
    {
        if (pending == null || verifier == null) return PollResult.FAILED;
        if (!Instant.now().isBefore(pending.expiresAt)) { cancel(); return PollResult.EXPIRED; }
        JsonObject body = new JsonObject();
        body.addProperty("verifier", verifier);
        Request request = new Request.Builder()
                .url(platformOrigin + "/v1/installation-links/requests/" + pending.requestId + "/redeem")
                .header("Cache-Control", "no-store")
                .post(RequestBody.create(JSON, gson.toJson(body))).build();
        try (Response response = http.newCall(request).execute())
        {
            if (response.code() == 202) return PollResult.WAITING;
            if (response.code() == 410) { cancel(); return PollResult.EXPIRED; }
            if (response.code() != 200 || response.body() == null)
            {
                // A 5xx is temporary: keep waiting.
                if (response.code() >= 500) return PollResult.WAITING;
                cancel();
                return PollResult.FAILED;
            }
            String installationId = new JsonParser().parse(response.body().string())
                    .getAsJsonObject().get("installationId").getAsString();
            if (!ID.matcher(installationId).matches()) throw new IOException("Unexpected link response");
            write(new Credential(installationId, verifier));
            pending = null;
            verifier = null;
            return PollResult.LINKED;
        }
    }

    synchronized void cancel()
    {
        pending = null;
        verifier = null;
    }

    /** Forgets this installation's link on this computer. */
    synchronized void forget() throws IOException
    {
        cancel();
        credential = null;
        file.deleteIfExists();
    }

    /** Only a rejected token (401) or a disabled account (403) forgets the link. */
    static boolean forgetsOn(int status, String body)
    {
        if (status == 401) return true;
        if (status != 403 || body == null) return false;
        try
        {
            JsonObject reply = new JsonParser().parse(body).getAsJsonObject();
            return reply.has("code") && "ACCOUNT_DISABLED".equals(reply.get("code").getAsString());
        }
        catch (RuntimeException notJson) { return false; }
    }

    private JsonObject post(String path, JsonObject body, int expected) throws IOException
    {
        Request request = new Request.Builder().url(platformOrigin + path)
                .header("Cache-Control", "no-store")
                .post(RequestBody.create(JSON, gson.toJson(body))).build();
        try (Response response = http.newCall(request).execute())
        {
            if (response.code() != expected || response.body() == null)
                throw new IOException("Link request failed: " + response.code());
            return new JsonParser().parse(response.body().string()).getAsJsonObject();
        }
    }

    private Credential read() throws IOException
    {
        if (!file.exists()) return null;
        if (!file.isFile() || file.size() < 2 || file.size() > MAX_BYTES) return null;
        ByteBuffer bytes = ByteBuffer.allocate((int) file.size());
        try (FileChannel channel = file.openFileChannel(StandardOpenOption.READ))
        {
            while (bytes.hasRemaining()) if (channel.read(bytes) < 0) return null;
        }
        try
        {
            JsonObject saved = new JsonParser().parse(new String(bytes.array(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String installationId = saved.get("installationId").getAsString();
            String token = saved.get("token").getAsString();
            if (!platformOrigin.equals(saved.get("origin").getAsString()) ||
                    !ID.matcher(installationId).matches() || token.length() < 32 || token.length() > 256)
                return null;
            return new Credential(installationId, token);
        }
        catch (RuntimeException invalid) { return null; }
    }

    private void write(Credential value) throws IOException
    {
        JsonObject saved = new JsonObject();
        saved.addProperty("origin", platformOrigin);
        saved.addProperty("installationId", value.installationId);
        saved.addProperty("token", value.token);
        byte[] bytes = gson.toJson(saved).getBytes(StandardCharsets.UTF_8);
        Filepath temp = file.getParent().createTempFile("sagaseer-link-", ".tmp");
        try
        {
            try (FileChannel channel = temp.openFileChannel(StandardOpenOption.WRITE))
            {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            temp.moveTo(file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally { temp.deleteIfExists(); }
        credential = value;
    }

    static String sha256Hex(String text)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
