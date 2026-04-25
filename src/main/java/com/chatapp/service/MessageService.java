package com.chatapp.service;

import com.chatapp.model.Message;
import com.chatapp.repository.FirebaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Message processing service — Phase 2 extended with group and search.
 *
 * Functional principles (all preserved from Phase 1):
 *   - Predicate<Message> for validation
 *   - UnaryOperator<Message> for SANITISE
 *   - Function<Message,Message> for status transforms
 *   - CompletableFuture pipelines with thenApply/thenCompose
 */
public final class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    // ── Predicates ────────────────────────────────────────────────────────────
    public static final Predicate<Message> HAS_CONTENT =
            msg -> msg.getContent() != null && !msg.getContent().isBlank();

    public static final Predicate<Message> NOT_TOO_LONG =
            msg -> msg.getContent() != null && msg.getContent().length() <= 2000;

    public static final Predicate<Message> IS_TEXT =
            msg -> msg.getType() == Message.MessageType.TEXT
                    || msg.getType() == Message.MessageType.BOT;

    public static final Predicate<Message> IS_FILE =
            msg -> msg.getType() == Message.MessageType.FILE;

    /** Composed: all text message conditions */
    public static final Predicate<Message> VALID_TEXT_MESSAGE =
            HAS_CONTENT.and(NOT_TOO_LONG).and(IS_TEXT);

    /** Valid group message: either text or file */
    public static final Predicate<Message> VALID_GROUP_MESSAGE =
            msg -> VALID_TEXT_MESSAGE.test(msg) || IS_FILE.test(msg);

    // ── Pure transformation functions ─────────────────────────────────────────
    public static final UnaryOperator<Message> SANITISE =
            msg -> {
                if (msg.getType() == Message.MessageType.FILE) return msg; // Don't sanitise files (metadata is set by server)
                return msg.isGroupMessage()
                        ? Message.forRoom(msg.getSenderId(), msg.getRoomId(), msg.getContent().trim())
                        : Message.of(msg.getSenderId(), msg.getReceiverId(), msg.getContent().trim());
            };

    public static final Function<Message, Message> MARK_DELIVERED =
            msg -> msg.withStatus(Message.MessageStatus.DELIVERED);

    public static final Function<Message, Message> MARK_READ =
            msg -> msg.withStatus(Message.MessageStatus.READ);

    // ── State ─────────────────────────────────────────────────────────────────
    private final FirebaseRepository repository;
    private final PresenceService    presenceService;

    public MessageService(FirebaseRepository repository, PresenceService presenceService) {
        this.repository      = repository;
        this.presenceService = presenceService;
    }

    // ── P2P message pipeline ──────────────────────────────────────────────────

    /**
     * Send a P2P message: validate → sanitise → persist → status update.
     */
    public CompletableFuture<Message> sendMessage(Message raw) {
        if (!VALID_GROUP_MESSAGE.test(raw)) // Using group message validator because it allows FILE types
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid message content"));

        Message sanitised = SANITISE.apply(raw);

        return repository.saveMessage(sanitised)
                .thenApply(saved -> presenceService.isOnline(saved.getReceiverId())
                        ? MARK_DELIVERED.apply(saved) : saved)
                .thenCompose(msg -> msg.getStatus() == Message.MessageStatus.DELIVERED
                        ? repository.updateMessageStatus(msg, msg.getStatus()).thenApply(v -> msg)
                        : CompletableFuture.completedFuture(msg))
                .thenApply(msg -> { log.info("P2P sent: {} → {}", msg.getSenderId(), msg.getReceiverId()); return msg; });
    }

    // ── Group message pipeline ────────────────────────────────────────────────

    /**
     * Send a group room message: validate → sanitise → persist → return.
     * File messages bypass the text sanitiser.
     */
    public CompletableFuture<Message> sendGroupMessage(Message raw) {
        if (!VALID_GROUP_MESSAGE.test(raw))
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid group message"));

        // Only text messages get sanitised; file messages keep their content
        Message toSave = IS_FILE.test(raw) ? raw : SANITISE.apply(raw);

        return repository.saveGroupMessage(toSave)
                .thenApply(saved -> {
                    log.info("Group msg saved: {} → room:{}", saved.getSenderId(), saved.getRoomId());
                    return saved;
                });
    }

    // ── History and search ────────────────────────────────────────────────────

    public CompletableFuture<List<Message>> getHistory(String userA, String userB) {
        return repository.fetchMessageHistory(userA, userB);
    }

    public CompletableFuture<List<Message>> getRoomHistory(String roomId) {
        return repository.fetchRoomMessages(roomId);
    }

    public CompletableFuture<List<Message>> searchRoom(String roomId, String query) {
        return repository.searchRoomMessages(roomId, query);
    }

    public CompletableFuture<List<Message>> searchP2P(String userA, String userB, String query) {
        return repository.searchP2PMessages(userA, userB, query);
    }

    public CompletableFuture<Message> markAsRead(Message message) {
        Message read = MARK_READ.apply(message);
        return repository.updateMessageStatus(read, Message.MessageStatus.READ)
                .thenApply(v -> read);
    }

    public Optional<String> validate(Message message) {
        if (!HAS_CONTENT.test(message))  return Optional.of("Content is empty");
        if (!NOT_TOO_LONG.test(message)) return Optional.of("Message too long (max 2000)");
        return Optional.empty();
    }
}
