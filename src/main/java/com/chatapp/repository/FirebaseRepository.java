package com.chatapp.repository;

import com.chatapp.config.AppConfig;
import com.chatapp.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Firebase Realtime Database + Auth repository — Phase 2 extended.
 *
 * New operations:
 *   - saveRoom, fetchRoom, fetchAllRooms, updateRoomMembers
 *   - setTyping
 *   - saveGroupMessage, fetchRoomMessages
 *   - searchMessages (functional stream pipeline)
 *
 * Functional principles:
 *   - All operations return CompletableFuture
 *   - Stream pipelines for data transformation
 *   - Optional for nullable results
 *   - Pure HTTP helper functions
 */
public final class FirebaseRepository {

    private static final Logger log = LoggerFactory.getLogger(FirebaseRepository.class);
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final AppConfig    config;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    public FirebaseRepository() {
        this.config = AppConfig.get();
        this.http   = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15,  java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.mapper = new ObjectMapper();
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    public CompletableFuture<User> registerUser(String email, String password, String displayName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> body = Map.of(
                        "email", email, "password", password, "returnSecureToken", true);
                String url  = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key="
                        + config.getFirebaseApiKey();
                String json = postJson(url, body);
                Map<?, ?> res = mapper.readValue(json, Map.class);
                String uid    = (String) res.get("localId");
                User user     = User.of(uid, email, displayName);
                saveUserProfile(user).join();
                return user;
            } catch (Exception e) {
                throw new RuntimeException("Registration failed: " + e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<User> loginUser(String email, String password) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> body = Map.of(
                        "email", email, "password", password, "returnSecureToken", true);
                String url  = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key="
                        + config.getFirebaseApiKey();
                String json = postJson(url, body);
                Map<?, ?> res = mapper.readValue(json, Map.class);
                String uid    = (String) res.get("localId");
                String name   = (String) res.get("displayName");
                if (name == null) name = email.split("@")[0];
                return User.of(uid, email, name);
            } catch (Exception e) {
                throw new RuntimeException("Login failed: " + e.getMessage(), e);
            }
        });
    }

    // ── User / Presence ───────────────────────────────────────────────────────

    public CompletableFuture<Void> saveUserProfile(User user) {
        return CompletableFuture.runAsync(() -> {
            try { putJson(dbUrl("users/" + user.getUid()), mapper.writeValueAsString(user)); }
            catch (Exception e) { log.error("saveUserProfile: {}", e.getMessage()); }
        });
    }

    public CompletableFuture<Void> setPresence(String uid, boolean online) {
        return CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> patch = Map.of(
                        "online", online, "lastSeen", System.currentTimeMillis());
                patchJson(dbUrl("users/" + uid), mapper.writeValueAsString(patch));
            } catch (Exception e) { log.error("setPresence: {}", e.getMessage()); }
        });
    }

    public CompletableFuture<List<User>> fetchAllUsers() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = getJson(dbUrl("users"));
                if (json == null || json.equals("null")) return List.of();
                Map<?, ?> map = mapper.readValue(json, Map.class);
                return map.values().stream()
                        .map(v -> { try { return mapper.readValue(
                                mapper.writeValueAsString(v), User.class); }
                            catch (Exception e) { return null; } })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("fetchAllUsers: {}", e.getMessage());
                return List.of();
            }
        });
    }

    // ── P2P Messages ──────────────────────────────────────────────────────────

    public CompletableFuture<Message> saveMessage(Message message) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String convoId = message.getConversationId()
                        .orElse(Message.buildConversationId(
                                message.getSenderId(), message.getReceiverId()));
                String url = dbUrl("conversations/" + convoId + "/messages/" + message.getId());
                putJson(url, mapper.writeValueAsString(message));
                return message;
            } catch (Exception e) {
                throw new RuntimeException("saveMessage failed: " + e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<List<Message>> fetchMessageHistory(String userA, String userB) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String convoId = Message.buildConversationId(userA, userB);
                String json    = getJson(dbUrl("conversations/" + convoId + "/messages"));
                if (json == null || json.equals("null")) return List.of();
                Map<?, ?> map = mapper.readValue(json, Map.class);
                return map.values().stream()
                        .map(v -> { try { return mapper.readValue(
                                mapper.writeValueAsString(v), Message.class); }
                            catch (Exception e) { return null; } })
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparingLong(Message::getTimestamp))
                        .limit(config.getMessageHistoryLimit())
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("fetchMessageHistory: {}", e.getMessage());
                return List.of();
            }
        });
    }

    public CompletableFuture<Void> updateMessageStatus(Message message,
                                                        Message.MessageStatus status) {
        return CompletableFuture.runAsync(() -> {
            try {
                String convoId = message.getConversationId().orElseThrow();
                putJson(dbUrl("conversations/" + convoId
                        + "/messages/" + message.getId() + "/status"),
                        "\"" + status.name() + "\"");
            } catch (Exception e) { log.error("updateMessageStatus: {}", e.getMessage()); }
        });
    }

    // ── Rooms ─────────────────────────────────────────────────────────────────

    public CompletableFuture<Room> saveRoom(Room room) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                putJson(dbUrl("rooms/" + room.getId()), mapper.writeValueAsString(room));
                log.info("Room saved: {}", room.getId());
                return room;
            } catch (Exception e) {
                throw new RuntimeException("saveRoom failed: " + e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<Optional<Room>> fetchRoom(String roomId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = getJson(dbUrl("rooms/" + roomId));
                if (json == null || json.equals("null")) return Optional.empty();
                return Optional.of(mapper.readValue(json, Room.class));
            } catch (Exception e) {
                log.error("fetchRoom: {}", e.getMessage());
                return Optional.empty();
            }
        });
    }

    public CompletableFuture<List<Room>> fetchAllRooms() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = getJson(dbUrl("rooms"));
                if (json == null || json.equals("null")) return List.of();
                Map<?, ?> map = mapper.readValue(json, Map.class);
                return map.values().stream()
                        .map(v -> { try { return mapper.readValue(
                                mapper.writeValueAsString(v), Room.class); }
                            catch (Exception e) { return null; } })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("fetchAllRooms: {}", e.getMessage());
                return List.of();
            }
        });
    }

    public CompletableFuture<Void> updateRoomMembers(Room room) {
        return CompletableFuture.runAsync(() -> {
            try {
                putJson(dbUrl("rooms/" + room.getId() + "/members"),
                        mapper.writeValueAsString(room.getMembers()));
            } catch (Exception e) { log.error("updateRoomMembers: {}", e.getMessage()); }
        });
    }

    // ── Group Messages ────────────────────────────────────────────────────────

    public CompletableFuture<Message> saveGroupMessage(Message message) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = dbUrl("rooms/" + message.getRoomId()
                        + "/messages/" + message.getId());
                putJson(url, mapper.writeValueAsString(message));
                return message;
            } catch (Exception e) {
                throw new RuntimeException("saveGroupMessage failed: " + e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<List<Message>> fetchRoomMessages(String roomId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = getJson(dbUrl("rooms/" + roomId + "/messages"));
                if (json == null || json.equals("null")) return List.of();
                Map<?, ?> map = mapper.readValue(json, Map.class);
                return map.values().stream()
                        .map(v -> { try { return mapper.readValue(
                                mapper.writeValueAsString(v), Message.class); }
                            catch (Exception e) { return null; } })
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparingLong(Message::getTimestamp))
                        .limit(config.getMessageHistoryLimit())
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("fetchRoomMessages: {}", e.getMessage());
                return List.of();
            }
        });
    }

    // ── Search (functional stream pipeline) ───────────────────────────────────

    /**
     * Search messages in a room by keyword.
     * Stream pipeline: fetch all → filter by keyword → sort → limit.
     * Pure functional: query string maps to filtered list via predicate.
     */
    public CompletableFuture<List<Message>> searchRoomMessages(String roomId, String query) {
        return fetchRoomMessages(roomId)
                .thenApply(messages -> {
                    String lowerQuery = query.toLowerCase();
                    return messages.stream()
                            .filter(msg -> msg.getContent().toLowerCase().contains(lowerQuery)
                                    || msg.getFileName().map(fn -> fn.toLowerCase()
                                            .contains(lowerQuery)).orElse(false))
                            .sorted(Comparator.comparingLong(Message::getTimestamp).reversed())
                            .limit(50)
                            .collect(Collectors.toList());
                });
    }

    /**
     * Search P2P messages between two users by keyword.
     */
    public CompletableFuture<List<Message>> searchP2PMessages(String userA,
                                                                String userB,
                                                                String query) {
        return fetchMessageHistory(userA, userB)
                .thenApply(messages -> {
                    String lowerQuery = query.toLowerCase();
                    return messages.stream()
                            .filter(msg -> msg.getContent().toLowerCase().contains(lowerQuery))
                            .sorted(Comparator.comparingLong(Message::getTimestamp).reversed())
                            .limit(50)
                            .collect(Collectors.toList());
                });
    }

    // ── Typing ────────────────────────────────────────────────────────────────

    public CompletableFuture<Void> setTyping(String uid, String roomId, boolean isTyping) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (isTyping) {
                    Map<String, Object> data = Map.of(
                            "isTyping", true,
                            "since",    System.currentTimeMillis());
                    putJson(dbUrl("typing/" + roomId + "/" + uid),
                            mapper.writeValueAsString(data));
                } else {
                    deleteJson(dbUrl("typing/" + roomId + "/" + uid));
                }
            } catch (Exception e) { log.error("setTyping: {}", e.getMessage()); }
        });
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private String dbUrl(String path) {
        return config.getFirebaseDatabaseUrl() + "/" + path + ".json";
    }

    private String postJson(String url, Object body) throws IOException {
        String json    = mapper.writeValueAsString(body);
        RequestBody rb = RequestBody.create(json, JSON_MEDIA);
        return execute(new Request.Builder().url(url).post(rb).build());
    }

    private void putJson(String url, String json) throws IOException {
        RequestBody rb = RequestBody.create(json, JSON_MEDIA);
        execute(new Request.Builder().url(url).put(rb).build());
    }

    private void patchJson(String url, String json) throws IOException {
        RequestBody rb = RequestBody.create(json, JSON_MEDIA);
        execute(new Request.Builder().url(url).patch(rb).build());
    }

    private String getJson(String url) throws IOException {
        return execute(new Request.Builder().url(url).get().build());
    }

    private void deleteJson(String url) throws IOException {
        execute(new Request.Builder().url(url).delete().build());
    }

    private String execute(Request req) throws IOException {
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                String body = res.body() != null ? res.body().string() : "";
                throw new IOException("HTTP " + res.code() + " at " + req.url() + ": " + body);
            }
            return res.body() != null ? res.body().string() : "null";
        }
    }
}
