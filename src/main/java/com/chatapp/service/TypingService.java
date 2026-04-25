package com.chatapp.service;

import com.chatapp.model.ChatFrame;
import com.chatapp.repository.FirebaseRepository;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Typing indicator service.
 *
 * Functional principles applied:
 *   - Consumer<Channel> for broadcasting typing events
 *   - Optional chaining for null-safe timer cancellation
 *   - CompletableFuture for async Firebase updates
 *   - No shared mutable state on method calls — only the timer map
 *
 * Behaviour:
 *   - TYPING_START: broadcast "X is typing..." to room, start 3s auto-stop timer
 *   - TYPING_STOP:  broadcast stop event, cancel timer
 *   - Auto-stop fires if user stops typing without sending (debounce)
 */
public final class TypingService {

    private static final Logger log    = LoggerFactory.getLogger(TypingService.class);
    private static final int    AUTO_STOP_SECONDS = 3;

    private final Map<String, ScheduledFuture<?>> typingTimers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "typing-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final FirebaseRepository  repository;
    private final GroupRoomService    groupRoomService;

    public TypingService(FirebaseRepository repository,
                          GroupRoomService groupRoomService) {
        this.repository       = repository;
        this.groupRoomService = groupRoomService;
    }

    /**
     * Mark a user as typing in a room.
     * Cancels any existing debounce timer and starts a new one.
     */
    public void startTyping(String uid, String roomId, String displayName) {
        // Cancel existing timer (user kept typing)
        cancelTimer(uid + ":" + roomId);

        // Update Firebase
        repository.setTyping(uid, roomId, true);

        // Broadcast to room members via Consumer<Channel>
        ChatFrame frame = ChatFrame.typingUpdate(uid, roomId, displayName, true);
        groupRoomService.broadcastToRoom(roomId, uid,
                ch -> sendFrame(ch, frame));

        // Auto-stop after 3 seconds of silence
        ScheduledFuture<?> stopTask = scheduler.schedule(
                () -> stopTyping(uid, roomId, displayName),
                AUTO_STOP_SECONDS, TimeUnit.SECONDS);
        typingTimers.put(uid + ":" + roomId, stopTask);
    }

    /**
     * Mark a user as stopped typing.
     * Called explicitly when user sends a message or clears input.
     */
    public void stopTyping(String uid, String roomId, String displayName) {
        cancelTimer(uid + ":" + roomId);
        repository.setTyping(uid, roomId, false);

        ChatFrame frame = ChatFrame.typingUpdate(uid, roomId, displayName, false);
        groupRoomService.broadcastToRoom(roomId, uid,
                ch -> sendFrame(ch, frame));
        log.debug("Stopped typing: {} in room {}", uid, roomId);
    }

    /** Called on user disconnect — stops all their active typing indicators */
    public void clearAllTyping(String uid, String displayName) {
        typingTimers.keySet().stream()
                .filter(key -> key.startsWith(uid + ":"))
                .forEach(key -> {
                    String roomId = key.substring(uid.length() + 1);
                    stopTyping(uid, roomId, displayName);
                });
    }

    private void cancelTimer(String key) {
        java.util.Optional.ofNullable(typingTimers.remove(key))
                .ifPresent(f -> f.cancel(false));
    }

    private void sendFrame(Channel ch, ChatFrame frame) {
        if (ch.isActive()) {
            com.chatapp.util.ChatFrameCodec.ENCODER.apply(frame)
                    .ifPresent(json -> ch.writeAndFlush(
                            new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(json)));
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
