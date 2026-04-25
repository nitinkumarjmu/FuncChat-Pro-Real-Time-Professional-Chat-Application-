package com.chatapp.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.*;

/**
 * Immutable Message model — Phase 2 extended.
 * Refactored to avoid Optional fields for better Jackson compatibility without extra modules.
 */
public final class Message {

    private final String  id;
    private final String  senderId;
    private final String  receiverId;   // null for group messages
    private final String  roomId;       // null for P2P messages
    private final String  content;
    private final long    timestamp;
    private final MessageType   type;
    private final MessageStatus status;
    private final String  conversationId;
    private final String  fileUrl;
    private final String  fileName;
    private final long    fileSize;
    private final Map<String, Boolean> readBy;

    public enum MessageType   { TEXT, FILE, SYSTEM, PRESENCE, BOT }
    public enum MessageStatus { SENT, DELIVERED, READ }

    @JsonCreator
    private Message(
            @JsonProperty("id")             String id,
            @JsonProperty("senderId")       String senderId,
            @JsonProperty("receiverId")     String receiverId,
            @JsonProperty("roomId")         String roomId,
            @JsonProperty("content")        String content,
            @JsonProperty("timestamp")      long timestamp,
            @JsonProperty("type")           MessageType type,
            @JsonProperty("status")         MessageStatus status,
            @JsonProperty("conversationId") String conversationId,
            @JsonProperty("fileUrl")        String fileUrl,
            @JsonProperty("fileName")       String fileName,
            @JsonProperty("fileSize")       long fileSize,
            @JsonProperty("readBy")         Map<String, Boolean> readBy
    ) {
        this.id             = id;
        this.senderId       = senderId;
        this.receiverId     = receiverId;
        this.roomId         = roomId;
        this.content        = content;
        this.timestamp      = timestamp;
        this.type           = type;
        this.status         = status;
        this.conversationId = conversationId;
        this.fileUrl        = fileUrl;
        this.fileName       = fileName;
        this.fileSize       = fileSize;
        this.readBy         = readBy != null
                ? Collections.unmodifiableMap(new HashMap<>(readBy))
                : Map.of();
    }

    // ── Static factory methods ────────────────────────────────────────────────

    public static Message of(String senderId, String receiverId, String content) {
        return new Message(
                UUID.randomUUID().toString(),
                senderId, receiverId, null, content,
                Instant.now().toEpochMilli(),
                MessageType.TEXT, MessageStatus.SENT,
                buildConversationId(senderId, receiverId),
                null, null, 0, Map.of()
        );
    }

    public static Message forRoom(String senderId, String roomId, String content) {
        return new Message(
                UUID.randomUUID().toString(),
                senderId, null, roomId, content,
                Instant.now().toEpochMilli(),
                MessageType.TEXT, MessageStatus.SENT,
                null, null, null, 0, Map.of()
        );
    }

    public static Message fileMessage(String senderId, String roomId,
                                       String fileUrl, String fileName, long fileSize) {
        return new Message(
                UUID.randomUUID().toString(),
                senderId, null, roomId,
                "Shared a file: " + fileName,
                Instant.now().toEpochMilli(),
                MessageType.FILE, MessageStatus.SENT,
                null, fileUrl, fileName, fileSize, Map.of()
        );
    }

    public static Message system(String senderId, String receiverId,
                                  String content, MessageType type) {
        return new Message(
                UUID.randomUUID().toString(),
                senderId, receiverId, null, content,
                Instant.now().toEpochMilli(),
                type, MessageStatus.SENT,
                null, null, null, 0, Map.of()
        );
    }

    public static Message botMessage(String roomId, String content, MessageType type) {
        return new Message(
                UUID.randomUUID().toString(),
                "BOT", null, roomId, content,
                Instant.now().toEpochMilli(),
                type, MessageStatus.DELIVERED,
                null, null, null, 0, Map.of()
        );
    }

    public static String buildConversationId(String userA, String userB) {
        return userA.compareTo(userB) < 0
                ? userA + "_" + userB
                : userB + "_" + userA;
    }

    // ── Immutable update methods ───────────────────────────────────────────────

    public Message withStatus(MessageStatus newStatus) {
        return new Message(id, senderId, receiverId, roomId, content,
                timestamp, type, newStatus, conversationId,
                fileUrl, fileName, fileSize, readBy);
    }

    public Message withReadBy(String uid) {
        Map<String, Boolean> updated = new HashMap<>(readBy);
        updated.put(uid, true);
        return new Message(id, senderId, receiverId, roomId, content,
                timestamp, type, status, conversationId,
                fileUrl, fileName, fileSize, updated);
    }

    public Message withFileUrl(String url) {
        return new Message(id, senderId, receiverId, roomId, content,
                timestamp, type, status, conversationId,
                url, fileName, fileSize, readBy);
    }

    public Message withFileName(String name) {
        return new Message(id, senderId, receiverId, roomId, content,
                timestamp, type, status, conversationId,
                fileUrl, name, fileSize, readBy);
    }

    public Message withFileSize(long size) {
        return new Message(id, senderId, receiverId, roomId, content,
                timestamp, type, status, conversationId,
                fileUrl, fileName, size, readBy);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getId()               { return id; }
    public String getSenderId()         { return senderId; }
    public String getReceiverId()       { return receiverId; }
    public String getRoomId()           { return roomId; }
    public String getContent()          { return content; }
    public long   getTimestamp()        { return timestamp; }
    public MessageType getType()        { return type; }
    public MessageStatus getStatus()    { return status; }
    
    @JsonProperty("conversationId")
    public String getConversationIdRaw() { return conversationId; }
    @JsonProperty("fileUrl")
    public String getFileUrlRaw()        { return fileUrl; }
    @JsonProperty("fileName")
    public String getFileNameRaw()       { return fileName; }

    @JsonIgnore
    public Optional<String> getConversationId() { return Optional.ofNullable(conversationId); }
    @JsonIgnore
    public Optional<String> getFileUrl()        { return Optional.ofNullable(fileUrl); }
    @JsonIgnore
    public Optional<String> getFileName()       { return Optional.ofNullable(fileName); }

    public long   getFileSize()         { return fileSize; }
    public Map<String, Boolean> getReadBy()     { return readBy; }

    @JsonIgnore
    public boolean isGroupMessage()     { return roomId != null; }

    @Override
    public String toString() {
        return isGroupMessage()
                ? String.format("[%s] %s → room:%s: %s", type, senderId, roomId, content)
                : String.format("[%s] %s → %s: %s", type, senderId, receiverId, content);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Message m)) return false;
        return Objects.equals(id, m.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
