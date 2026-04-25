package com.chatapp.webhook;

import com.chatapp.config.AppConfig;
import com.chatapp.model.ChatFrame;
import com.chatapp.model.Message;
import com.chatapp.service.GroupRoomService;
import com.chatapp.service.MessageService;
import com.chatapp.service.PresenceService;
import com.chatapp.util.ChatFrameCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Lightweight HTTP webhook server for bot/integration notifications.
 *
 * External systems (CI/CD, order management, etc.) POST to:
 *   POST http://your-server:8081/webhook/{roomId}
 *   Body: { "text": "Build #42 passed", "source": "CI/CD" }
 *
 * The message is injected into the specified room as a BOT message
 * and broadcast to all online members.
 *
 * Functional principles:
 *   - Consumer<Channel> for broadcasting
 *   - CompletableFuture for async persistence
 *   - Optional chaining for null-safe payload parsing
 */
public final class BotWebhookServer {

    private static final Logger log = LoggerFactory.getLogger(BotWebhookServer.class);

    private final HttpServer       httpServer;
    private final GroupRoomService groupRoomService;
    private final MessageService   messageService;
    @SuppressWarnings("unused")
    private final PresenceService  presenceService;
    private final ObjectMapper     mapper = new ObjectMapper();

    public BotWebhookServer(GroupRoomService groupRoomService,
                             MessageService messageService,
                             PresenceService presenceService) throws IOException {
        this.groupRoomService = groupRoomService;
        this.messageService   = messageService;
        this.presenceService  = presenceService;

        int port = AppConfig.get().getWebhookPort();
        this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        this.httpServer.createContext("/webhook/", this::handleWebhook);
        this.httpServer.createContext("/health",   this::handleHealth);
        this.httpServer.setExecutor(Executors.newFixedThreadPool(4));
        log.info("Webhook server configured on port {}", port);
    }

    public void start() {
        httpServer.start();
        log.info("Webhook server started — POST /webhook/{{roomId}}");
    }

    public void stop() {
        httpServer.stop(2);
        log.info("Webhook server stopped");
    }

    // ── Webhook handler ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleWebhook(HttpExchange exchange) throws IOException {
        // Only accept POST
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        // Extract roomId from path: /webhook/{roomId}
        String path   = exchange.getRequestURI().getPath();
        String roomId = path.substring("/webhook/".length()).trim();

        if (roomId.isEmpty()) {
            respond(exchange, 400, "{\"error\":\"roomId required in path\"}");
            return;
        }

        // Read and parse body
        String body;
        try (InputStream is = exchange.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        Map<String, Object> payload;
        try {
            payload = mapper.readValue(body, Map.class);
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"Invalid JSON body\"}");
            return;
        }

        String text   = (String) payload.getOrDefault("text", "");
        String source = (String) payload.getOrDefault("source", "Bot");

        if (text.isBlank()) {
            respond(exchange, 400, "{\"error\":\"text field required\"}");
            return;
        }

        // Verify room exists
        groupRoomService.getRoom(roomId)
                .thenAccept(optional -> optional.ifPresentOrElse(
                        room -> {
                            // Build bot message
                            String content = "[" + source + "] " + text;
                            Message botMsg = Message.botMessage(roomId, content, Message.MessageType.BOT);

                            // Persist and broadcast
                            messageService.sendGroupMessage(botMsg)
                                    .thenAccept(saved -> {
                                        ChatFrame frame = ChatFrame.botMessage(saved);
                                        Consumer<Channel> broadcast = ch ->
                                                ChatFrameCodec.ENCODER.apply(frame)
                                                        .filter(json -> ch.isActive())
                                                        .ifPresent(json -> ch.writeAndFlush(
                                                                new TextWebSocketFrame(json)));
                                        groupRoomService.broadcastToRoom(roomId, "BOT", broadcast);
                                        log.info("Bot message sent to room {}: {}", roomId, content);
                                    });
                        },
                        () -> log.warn("Webhook: room not found: {}", roomId)
                ));

        respond(exchange, 200, "{\"status\":\"accepted\"}");
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"status\":\"ok\",\"service\":\"FuncChat Pro Webhook\"}");
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
