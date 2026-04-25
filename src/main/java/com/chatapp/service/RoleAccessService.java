package com.chatapp.service;

import com.chatapp.model.Room;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Role-based access control service.
 *
 * Functional principles applied:
 *   - BiPredicate<String,Room> for parameterised permission checks
 *   - Predicate composition with .and() and .negate()
 *   - Optional<String> for null-safe permission error messages
 *   - Pure functions: same inputs always give same access decision
 *
 * Roles (from Room.Role enum):
 *   ADMIN  — can create/delete rooms, remove members, promote others
 *   MEMBER — can send messages, join rooms, leave rooms
 */
public final class RoleAccessService {

    // ── BiPredicates: (uid, room) → boolean ──────────────────────────────────

    /** User is a member of the room */
    public static final BiPredicate<String, Room> CAN_READ =
            (uid, room) -> room.hasMember(uid);

    /** User is a member of the room (can send messages) */
    public static final BiPredicate<String, Room> CAN_WRITE =
            (uid, room) -> room.hasMember(uid);

    /** User is an admin of the room */
    public static final BiPredicate<String, Room> CAN_MANAGE =
            (uid, room) -> room.isAdmin(uid);

    /** User is the creator of the room */
    public static final BiPredicate<String, Room> IS_CREATOR =
            (uid, room) -> uid.equals(room.getCreatedBy());

    // ── Single-argument predicates (curried) ──────────────────────────────────

    /** Returns Predicate<Room> — is this uid a member? */
    public static Predicate<Room> memberOf(String uid) {
        return room -> CAN_READ.test(uid, room);
    }

    /** Returns Predicate<Room> — is this uid an admin? */
    public static Predicate<Room> adminOf(String uid) {
        return room -> CAN_MANAGE.test(uid, room);
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private final GroupRoomService groupRoomService;

    public RoleAccessService(GroupRoomService groupRoomService) {
        this.groupRoomService = groupRoomService;
    }

    // ── Permission checks ─────────────────────────────────────────────────────

    /**
     * Check if uid can send a message to a room.
     * Returns Optional.empty() if allowed; Optional<String> with reason if denied.
     */
    public CompletableFuture<Optional<String>> canSendToRoom(String uid, String roomId) {
        return groupRoomService.getRoom(roomId)
                .thenApply(optional -> optional
                        .map(room -> CAN_WRITE.test(uid, room)
                                ? Optional.<String>empty()
                                : Optional.of("You are not a member of this room"))
                        .orElse(Optional.of("Room not found: " + roomId)));
    }

    /**
     * Check if uid can perform an admin action (remove user, promote, etc.).
     */
    public CompletableFuture<Optional<String>> canManageRoom(String uid, String roomId) {
        return groupRoomService.getRoom(roomId)
                .thenApply(optional -> optional
                        .map(room -> CAN_MANAGE.test(uid, room)
                                ? Optional.<String>empty()
                                : Optional.of("Admin privileges required"))
                        .orElse(Optional.of("Room not found: " + roomId)));
    }

    /**
     * Check if uid can read messages in a room.
     */
    public CompletableFuture<Optional<String>> canReadRoom(String uid, String roomId) {
        return groupRoomService.getRoom(roomId)
                .thenApply(optional -> optional
                        .map(room -> CAN_READ.test(uid, room)
                                ? Optional.<String>empty()
                                : Optional.of("You are not a member of this room"))
                        .orElse(Optional.of("Room not found: " + roomId)));
    }

    /**
     * Synchronous check using cached room (for hot paths like message dispatch).
     * Returns false if room is not in cache — falls back to false (deny).
     */
    public boolean isMemberSync(String uid, String roomId) {
        return groupRoomService.isMember(roomId, uid);
    }

    public boolean isAdminSync(String uid, String roomId) {
        return groupRoomService.isAdmin(roomId, uid);
    }
}
