package com.savecopilot;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Communicates with the GitHub Copilot CLI via the Agent Client Protocol (ACP).
 * ACP uses JSON-RPC 2.0 over stdin/stdout of a `copilot --acp` subprocess.
 */
public class CopilotAcpClient implements Closeable {

    private static final Gson GSON = new GsonBuilder().create();

    private final Process process;
    private final PrintWriter stdin;
    private final AtomicInteger idCounter = new AtomicInteger(1);

    /** Pending requests awaiting a JSON-RPC response (by request id). */
    private final Map<Integer, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();

    /** Active session id (returned by session.create). */
    private String sessionId;

    /** Background reader thread. */
    private final Thread readerThread;

    /** Consumer called for each streamed text chunk from the agent. */
    private volatile Consumer<String> chunkConsumer;

    public CopilotAcpClient(String copilotCmd, String extraArgs) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(copilotCmd);
        cmd.add("--acp");
        if (extraArgs != null && !extraArgs.isBlank()) {
            Collections.addAll(cmd, extraArgs.trim().split("\\s+"));
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false); // keep stderr separate
        this.process = pb.start();

        this.stdin = new PrintWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true
        );

        // Drain stderr in background (suppress copilot UI output)
        Thread stderrDrain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    // uncomment for debugging: System.err.println("[copilot] " + line);
                }
            } catch (IOException ignored) {}
        }, "copilot-stderr");
        stderrDrain.setDaemon(true);
        stderrDrain.start();

        // Main reader: process JSON-RPC messages from copilot
        this.readerThread = new Thread(this::readLoop, "copilot-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    // ──────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────

    /** Initialize the ACP handshake. Must be called first. */
    public void initialize() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", 1);
        params.add("capabilities", new JsonObject());
        sendRequest("initialize", params).get(30, TimeUnit.SECONDS);
    }

    /** Create a new agent session with the given system prompt. */
    public void createSession(String systemPrompt) throws Exception {
        JsonObject params = new JsonObject();

        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        JsonArray content = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", systemPrompt);
        content.add(textPart);
        sysMsg.add("content", content);
        messages.add(sysMsg);

        params.add("messages", messages);
        params.addProperty("mode", "agent");

        JsonObject result = sendRequest("session.create", params).get(30, TimeUnit.SECONDS);
        if (result != null && result.has("sessionId")) {
            this.sessionId = result.get("sessionId").getAsString();
        } else if (result != null && result.has("id")) {
            this.sessionId = result.get("id").getAsString();
        }
    }

    /**
     * Send a user message and block until the agent finishes responding.
     *
     * @param userText     the user's message
     * @param onChunk      called for each streamed text delta (may be null)
     * @return             the full response text
     */
    public String sendMessage(String userText, Consumer<String> onChunk) throws Exception {
        if (sessionId == null) {
            throw new IllegalStateException("No active session. Call createSession() first.");
        }

        StringBuilder fullResponse = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);

        // Capture streaming chunks
        this.chunkConsumer = chunk -> {
            if (onChunk != null) onChunk.accept(chunk);
            fullResponse.append(chunk);
        };

        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);

        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", userText);
        content.add(textPart);
        userMsg.add("content", content);
        messages.add(userMsg);

        params.add("messages", messages);

        // session.send streams events; we also wait for the RPC response
        CompletableFuture<JsonObject> future = sendRequest("session.send", params);

        // Wait for either: RPC response or a timeout
        future.get(120, TimeUnit.SECONDS);

        this.chunkConsumer = null;
        return fullResponse.toString().trim();
    }

    // ──────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────

    private CompletableFuture<JsonObject> sendRequest(String method, JsonObject params) {
        int id = idCounter.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", id);
        req.addProperty("method", method);
        req.add("params", params);

        stdin.println(GSON.toJson(req));
        return future;
    }

    /** Continuously reads JSON-RPC messages from copilot stdout. */
    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    handleMessage(GSON.fromJson(line, JsonObject.class));
                } catch (JsonSyntaxException e) {
                    // Skip non-JSON lines (e.g., copilot startup output)
                }
            }
        } catch (IOException e) {
            // Process closed
        }
        // Fail any pending requests
        pendingRequests.values().forEach(f -> f.completeExceptionally(new EOFException("copilot process ended")));
    }

    private void handleMessage(JsonObject msg) {
        if (msg.has("id") && !msg.get("id").isJsonNull()) {
            // JSON-RPC response
            int id = msg.get("id").getAsInt();
            CompletableFuture<JsonObject> future = pendingRequests.remove(id);
            if (future != null) {
                if (msg.has("error")) {
                    future.completeExceptionally(
                        new RuntimeException("ACP error: " + msg.get("error").toString())
                    );
                } else {
                    JsonElement result = msg.get("result");
                    future.complete(result != null && result.isJsonObject()
                        ? result.getAsJsonObject() : new JsonObject());
                }
            }
        } else if (msg.has("method")) {
            // Notification / server-pushed event
            handleNotification(msg);
        }
    }

    private void handleNotification(JsonObject msg) {
        String method = msg.has("method") ? msg.get("method").getAsString() : "";
        JsonElement paramsEl = msg.get("params");
        if (paramsEl == null || !paramsEl.isJsonObject()) return;
        JsonObject params = paramsEl.getAsJsonObject();

        switch (method) {
            case "session.event" -> handleSessionEvent(params);
            default -> { /* ignore other notifications */ }
        }
    }

    private void handleSessionEvent(JsonObject params) {
        // Events carry a "type" field describing what happened
        if (!params.has("type")) return;
        String type = params.get("type").getAsString();

        Consumer<String> consumer = this.chunkConsumer;
        if (consumer == null) return;

        switch (type) {
            case "assistant.message_delta" -> {
                // Streaming text delta
                if (params.has("delta")) {
                    JsonObject delta = params.getAsJsonObject("delta");
                    if (delta.has("text")) {
                        consumer.accept(delta.get("text").getAsString());
                    }
                }
            }
            case "assistant.message" -> {
                // Full message (non-streaming fallback)
                if (params.has("message")) {
                    JsonObject message = params.getAsJsonObject("message");
                    if (message.has("content")) {
                        JsonElement content = message.get("content");
                        if (content.isJsonPrimitive()) {
                            consumer.accept(content.getAsString());
                        } else if (content.isJsonArray()) {
                            for (JsonElement el : content.getAsJsonArray()) {
                                if (el.isJsonObject()) {
                                    JsonObject part = el.getAsJsonObject();
                                    if (part.has("text")) {
                                        consumer.accept(part.get("text").getAsString());
                                    }
                                }
                            }
                        }
                    }
                }
            }
            default -> { /* ignore other event types */ }
        }
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroy();
        }
    }
}
