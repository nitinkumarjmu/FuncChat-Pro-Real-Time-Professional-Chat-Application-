package com.chatapp.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * WebSocket frame protocol model — Phase 2 extended.
 *
 * New actions vs Phase 1:
 *   CREATE_ROOM, JOIN_ROOM, LEAVE_ROOM, ROOM_CREATED, ROOM_UPDATED
 *   SEND_GROUP_MSG, GROUP_MESSAGE
 *   TYPING_START, TYPING_STOP, TYPING_UPDATE
 *   UPLOAD_FILE, FILE_SHARED
 *   SEARCH_MESSAGES, SEARCH_RESULTS
 *   REMOVE_USER, USER_REMOVED
 *   BOT_MESSAGE
 */
public final class ChatFrame {

    public enum Action {
        // Auth
        LOGIN, REGISTER, LOGIN_SUCCESS, LOGIN_ERROR,

        // P2P Chat (Phase 1, unchanged)
        SEND_MESSAGE, RECEIVE_MESSAGE, MESSAGE_STATUS,

        // Group Rooms (Phase 2)
        CREATE_ROOM, JOIN_ROOM, LEAVE_ROOM,
        ROOM_CREATED, ROOM_UPDATED, ROOM_LIST,

        // Group Messages (Phase 2)
        SEND_GROUP_MSG, GROUP_MESSAGE,

        // Typing Indicators (Phase 2)
        TYPING_START, TYPING_STOP, TYPING_UPDATE,

        // File Sharing (Phase 2)
        UPLOAD_FILE, FILE_SHARED,

        // Search (Phase 2)
        SEARCH_MESSAGES, SEARCH_RESULTS,

        // Admin Controls (Phase 2)
        REMOVE_USER, USER_REMOVED, PROMOTE_USER,

        // Bot / Webhook (Phase 2)
        BOT_MESSAGE,

        // Presence (Phase 1, unchanged)
        USER_ONLINE, USER_OFFLINE, USER_LIST,

        // System
        ERROR, PING, PONG
    }

    private final Action action;
    private final Object payload;
    private final long   timestamp;

    @JsonCreator
    public ChatFrame(
            @JsonProperty("action")    Action action,
            @JsonProperty("payload")   Object payload,
            @JsonProperty("timestamp") long timestamp
    ) {
        this.action    = action;
        this.payload   = payload;
        this.timestamp = timestamp;
    }

    // ── Static factory methods ────────────────────────────────────────────────

    public static ChatFrame of(Action action, Object payload) {
        return new ChatFrame(action, payload, System.currentTimeMillis());
    }

    public static ChatFrame loginSuccess(User user)        { return of(Action.LOGIN_SUCCESS, user); }
    public static ChatFrame receiveMessage(Message msg)    { return of(Action.RECEIVE_MESSAGE, msg); }
    public static ChatFrame groupMessage(Message msg)      { return of(Action.GROUP_MESSAGE, msg); }
    public static ChatFrame roomCreated(Room room)         { return of(Action.ROOM_CREATED, room); }
    public static ChatFrame fileShared(Message msg)        { return of(Action.FILE_SHARED, msg); }
    public static ChatFrame botMessage(Message msg)        { return of(Action.BOT_MESSAGE, msg); }
    public static ChatFrame error(String msg)              { return of(Action.ERROR, msg); }
    public static ChatFrame pong()                         { return of(Action.PONG, null); }
    public static ChatFrame presenceUpdate(User user, boolean online) {
        return of(online ? Action.USER_ONLINE : Action.USER_OFFLINE, user);
    }
    public static ChatFrame typingUpdate(String uid, String roomId,
                                          String displayName, boolean typing) {
        return of(Action.TYPING_UPDATE, new TypingPayload(uid, roomId, displayName, typing));
    }
    public static ChatFrame userRemoved(String uid, String roomId) {
        return of(Action.USER_REMOVED, new RoomUserPayload(uid, roomId));
    }

    public Action getAction()    { return action; }
    public Object getPayload()   { return payload; }
    public long   getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return String.format("ChatFrame{action=%s}", action);
    }

    // ── Nested payload types ─────────────────────────────────────────────────

    public record TypingPayload(String uid, String roomId,
                                 String displayName, boolean typing) {}

    public record RoomUserPayload(String uid, String roomId) {}

    public record SearchPayload(String query, String roomId) {}

    public record FileUploadPayload(String fileName, String base64Data,
                                     String roomId, long fileSize) {}
}
