package com.chatapp.util;

import com.chatapp.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Function;

/**
 * Pure encode/decode utility for ChatFrame ↔ JSON.
 *
 * Functional principles:
 *   - Static pure functions (same input → same output)
 *   - Optional<T> for safe parse results
 *   - Function<String, Optional<ChatFrame>> as composable decoder
 */
public final class ChatFrameCodec {

    private static final Logger log    = LoggerFactory.getLogger(ChatFrameCodec.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatFrameCodec() {}

    public static Optional<String> encode(ChatFrame frame) {
        try   { return Optional.of(MAPPER.writeValueAsString(frame)); }
        catch (Exception e) { log.error("encode failed: {}", e.getMessage()); return Optional.empty(); }
    }

    public static Optional<ChatFrame> decode(String json) {
        try   { return Optional.of(MAPPER.readValue(json, ChatFrame.class)); }
        catch (Exception e) { log.warn("decode failed for: {}", json); return Optional.empty(); }
    }

    public static Optional<Message> payloadAsMessage(ChatFrame frame) {
        try {
            Object p = frame.getPayload();
            if (p == null) return Optional.empty();
            return Optional.of(MAPPER.readValue(MAPPER.writeValueAsString(p), Message.class));
        } catch (Exception e) { 
            e.printStackTrace();
            return Optional.empty(); 
        }
    }

    public static Optional<User> payloadAsUser(ChatFrame frame) {
        try {
            Object p = frame.getPayload();
            if (p == null) return Optional.empty();
            return Optional.of(MAPPER.readValue(MAPPER.writeValueAsString(p), User.class));
        } catch (Exception e) { 
            e.printStackTrace();
            return Optional.empty(); 
        }
    }

    public static Optional<Room> payloadAsRoom(ChatFrame frame) {
        try {
            Object p = frame.getPayload();
            if (p == null) return Optional.empty();
            return Optional.of(MAPPER.readValue(MAPPER.writeValueAsString(p), Room.class));
        } catch (Exception e) { 
            System.err.println("Failed to parse Room payload: " + e.getMessage());
            e.printStackTrace();
            return Optional.empty(); 
        }
    }

    @SuppressWarnings("unchecked")
    public static Optional<java.util.Map<String, Object>> payloadAsMap(ChatFrame frame) {
        try {
            Object p = frame.getPayload();
            if (p == null) return Optional.empty();
            return Optional.of(MAPPER.readValue(MAPPER.writeValueAsString(p),
                    java.util.Map.class));
        } catch (Exception e) { return Optional.empty(); }
    }

    public static Optional<String> payloadAsString(ChatFrame frame) {
        return Optional.ofNullable(frame.getPayload()).map(Object::toString);
    }

    /** Composable decoder — can be passed as Function reference */
    public static final Function<String, Optional<ChatFrame>> DECODER = ChatFrameCodec::decode;

    /** Composable encoder — can be passed as Function reference */
    public static final Function<ChatFrame, Optional<String>> ENCODER = ChatFrameCodec::encode;
}
