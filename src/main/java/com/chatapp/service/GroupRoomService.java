package com.chatapp.service;

import com.chatapp.model.Room;
import com.chatapp.repository.FirebaseRepository;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Group room lifecycle service.
 *
 * Functional principles applied:
 *   - Predicate<Map<String,Room.Role>> for composable membership checks
 *   - Consumer<Channel> for room broadcasting
 *   - CompletableFuture for async room operations
 *   - Stream pipelines for member queries
 *   - ConcurrentHashMap for thread-safe in-memory room cache
 */
public final class GroupRoomService {

    private static final Logger log = LoggerFactory.getLogger(GroupRoomService.class);

    // ── Reusable predicates (pure functions) ─────────────────────────────────

    /** Predicate: uid is a member of a given member map */
    public static Predicate<Map<String, Room.Role>> IS_MEMBER(String uid) {
        return members -> members.containsKey(uid);
    }

    /** Predicate: uid has ADMIN role */
    public static Predicate<Map<String, Room.Role>> IS_ADMIN(String uid) {
        return members -> Room.Role.ADMIN.equals(members.get(uid));
    }

    /** Predicate: room has at least one remaining member */
    public static final Predicate<Room> HAS_MEMBERS = room -> room.memberCount() > 0;

    /** Predicate: room name is valid */
    public static final Predicate<String> VALID_ROOM_NAME =
            name -> name != null && !name.isBlank()
                    && name.length() >= 2 && name.length() <= 64;

    // ── State ─────────────────────────────────────────────────────────────────
    /** In-memory room cache for fast lookups — refreshed from Firebase on startup */
    private final Map<String, Room> roomCache     = new ConcurrentHashMap<>();
    private final FirebaseRepository repository;
    private final PresenceService    presenceService;

    public GroupRoomService(FirebaseRepository repository, PresenceService presenceService) {
        this.repository      = repository;
        this.presenceService = presenceService;
    }

    // ── Room lifecycle ────────────────────────────────────────────────────────

    /**
     * Create a new room.
     * Pipeline: validate → build immutable Room → persist → cache → return.
     */
    public CompletableFuture<Room> createRoom(String name, String description,
                                               String creatorUid) {
        if (!VALID_ROOM_NAME.test(name))
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Room name must be 2–64 characters"));

        Room room = Room.of(name.trim(), description != null ? description.trim() : "", creatorUid);

        return repository.saveRoom(room)
                .thenApply(saved -> {
                    roomCache.put(saved.getId(), saved);
                    log.info("Room created: {} by {}", saved.getName(), creatorUid);
                    return saved;
                });
    }

    /**
     * Add a user to a room.
     * Returns updated Room (immutable — new instance with member added).
     */
    public CompletableFuture<Room> joinRoom(String roomId, String uid) {
        return getRoom(roomId)
                .thenCompose(optional -> optional
                        .map(room -> {
                            if (room.hasMember(uid))
                                return CompletableFuture.completedFuture(room);

                            Room updated = room.withMember(uid);
                            roomCache.put(roomId, updated);
                            return repository.updateRoomMembers(updated)
                                    .thenApply(v -> {
                                        log.info("User {} joined room {}", uid, roomId);
                                        return updated;
                                    });
                        })
                        .orElse(CompletableFuture.failedFuture(
                                new IllegalArgumentException("Room not found: " + roomId)))
                );
    }

    /**
     * Remove a user from a room.
     * Admin-gated: caller must pass the admin uid for permission check.
     */
    public CompletableFuture<Room> removeUser(String roomId,
                                               String adminUid,
                                               String targetUid) {
        return getRoom(roomId)
                .thenCompose(optional -> optional
                        .map(room -> {
                            if (!IS_ADMIN(adminUid).test(room.getMembers()))
                                return CompletableFuture.<Room>failedFuture(
                                        new SecurityException("Only admins can remove users"));

                            Room updated = room.withoutMember(targetUid);
                            roomCache.put(roomId, updated);
                            return repository.updateRoomMembers(updated)
                                    .thenApply(v -> {
                                        log.info("Admin {} removed {} from room {}",
                                                adminUid, targetUid, roomId);
                                        return updated;
                                    });
                        })
                        .orElse(CompletableFuture.failedFuture(
                                new IllegalArgumentException("Room not found: " + roomId)))
                );
    }

    /**
     * Leave a room voluntarily.
     */
    public CompletableFuture<Room> leaveRoom(String roomId, String uid) {
        return getRoom(roomId)
                .thenCompose(optional -> optional
                        .map(room -> {
                            Room updated = room.withoutMember(uid);
                            roomCache.put(roomId, updated);
                            return repository.updateRoomMembers(updated)
                                    .thenApply(v -> updated);
                        })
                        .orElse(CompletableFuture.completedFuture(null))
                );
    }

    // ── Broadcasting ──────────────────────────────────────────────────────────

    /**
     * Broadcast a frame to all online members of a room.
     *
     * Uses Consumer<Channel> to isolate the side-effect (sending) from
     * the pure member-lookup logic. Same pattern as PresenceService.broadcastToAll().
     */
    public void broadcastToRoom(String roomId, String excludeUid,
                                 Consumer<Channel> action) {
        Optional.ofNullable(roomCache.get(roomId))
                .ifPresent(room -> {
                    Set<String> memberUids = new HashSet<>(room.getMemberUids());
                    presenceService.broadcastToMembers(memberUids, excludeUid, action);
                });
    }

    /** Check if a user is a member of the cached room */
    public boolean isMember(String roomId, String uid) {
        return Optional.ofNullable(roomCache.get(roomId))
                .map(room -> room.hasMember(uid))
                .orElse(false);
    }

    /** Check if a user is an admin of the cached room */
    public boolean isAdmin(String roomId, String uid) {
        return Optional.ofNullable(roomCache.get(roomId))
                .map(room -> room.isAdmin(uid))
                .orElse(false);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Get room from cache, then Firebase if not cached.
     * Returns Optional to force null-safe handling.
     */
    public CompletableFuture<Optional<Room>> getRoom(String roomId) {
        Room cached = roomCache.get(roomId);
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));
        return repository.fetchRoom(roomId)
                .thenApply(opt -> { opt.ifPresent(r -> roomCache.put(r.getId(), r)); return opt; });
    }

    /** Get all rooms — from cache or Firebase */
    public CompletableFuture<List<Room>> getAllRooms() {
        if (!roomCache.isEmpty())
            return CompletableFuture.completedFuture(List.copyOf(roomCache.values()));
        return repository.fetchAllRooms()
                .thenApply(rooms -> {
                    rooms.forEach(r -> roomCache.put(r.getId(), r));
                    return rooms;
                });
    }

    /**
     * Get all rooms a specific user belongs to.
     * Pure stream pipeline: filter cached rooms by membership predicate.
     */
    public List<Room> getRoomsForUser(String uid) {
        return roomCache.values().stream()
                .filter(room -> IS_MEMBER(uid).test(room.getMembers()))
                .sorted(Comparator.comparingLong(Room::getCreatedAt))
                .collect(Collectors.toList());
    }

    /** Load all rooms from Firebase into cache */
    public CompletableFuture<Void> loadRoomsFromDatabase() {
        return repository.fetchAllRooms()
                .thenAccept(rooms -> {
                    rooms.forEach(r -> roomCache.put(r.getId(), r));
                    log.info("Loaded {} rooms into cache", rooms.size());
                });
    }

    /**
     * Persist an updated room (e.g. after a role change) to Firebase and refresh the cache.
     * Returns the saved room so callers can chain .thenAccept(saved -> ...).
     */
    public CompletableFuture<Room> persistRoom(Room room) {
        return repository.saveRoom(room)
                .thenApply(saved -> {
                    roomCache.put(saved.getId(), saved);
                    log.info("Room persisted: {} ({})", saved.getName(), saved.getId());
                    return saved;
                });
    }
}
