package com.chatapp.service;

import com.chatapp.model.User;
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
 * Presence service — Phase 2 extended with room-aware broadcasting.
 *
 * Functional principles:
 *   - Predicate<User> for filtering
 *   - Consumer<Channel> for broadcasting (side-effect isolation)
 *   - Stream pipelines for online user queries
 *   - ConcurrentHashMap for thread safety
 */
public final class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    // ── Reusable predicates ───────────────────────────────────────────────────
    public static final Predicate<User> IS_ONLINE       = User::isOnline;
    public static final Predicate<User> IS_OFFLINE      = IS_ONLINE.negate();
    public static final Predicate<User> RECENTLY_ACTIVE =
            user -> System.currentTimeMillis() - user.getLastSeen() < 5 * 60 * 1000;

    // ── State ─────────────────────────────────────────────────────────────────
    private final Map<String, Channel> connectedChannels = new ConcurrentHashMap<>();
    private final Map<String, User>    userCache         = new ConcurrentHashMap<>();
    private final FirebaseRepository   repository;

    public PresenceService(FirebaseRepository repository) {
        this.repository = repository;
    }

    public CompletableFuture<Void> setOnline(User user, Channel channel) {
        return CompletableFuture.runAsync(() -> {
            connectedChannels.put(user.getUid(), channel);
            userCache.put(user.getUid(), user.withOnlineStatus(true));
            log.info("Online: {}", user.getDisplayName());
        }).thenCompose(v -> repository.setPresence(user.getUid(), true));
    }

    public CompletableFuture<Void> setOffline(String uid) {
        return CompletableFuture.runAsync(() -> {
            connectedChannels.remove(uid);
            userCache.computeIfPresent(uid, (id, u) -> u.withOnlineStatus(false));
            log.info("Offline: {}", uid);
        }).thenCompose(v -> repository.setPresence(uid, false));
    }

    public List<User> getOnlineUsers() {
        return userCache.values().stream()
                .filter(IS_ONLINE)
                .collect(Collectors.toUnmodifiableList());
    }

    public Optional<Channel> getChannel(String uid) {
        return Optional.ofNullable(connectedChannels.get(uid));
    }

    public boolean isOnline(String uid) {
        return connectedChannels.containsKey(uid);
    }

    /** Broadcast to all online users except the sender */
    public void broadcastToAll(String excludeUid, Consumer<Channel> action) {
        connectedChannels.entrySet().stream()
                .filter(e -> !e.getKey().equals(excludeUid))
                .filter(e -> e.getValue().isActive())
                .map(Map.Entry::getValue)
                .forEach(action);
    }

    /**
     * Broadcast to a specific set of UIDs (for room members).
     * Uses stream pipeline with filter on provided member uid set.
     */
    public void broadcastToMembers(Set<String> memberUids,
                                    String excludeUid,
                                    Consumer<Channel> action) {
        memberUids.stream()
                .filter(uid -> !uid.equals(excludeUid))
                .filter(this::isOnline)
                .map(uid -> connectedChannels.get(uid))
                .filter(Objects::nonNull)
                .filter(Channel::isActive)
                .forEach(action);
    }

    /** Send to a specific user — returns true if delivered */
    public boolean sendToUser(String uid, Consumer<Channel> action) {
        return getChannel(uid)
                .filter(Channel::isActive)
                .map(ch -> { action.accept(ch); return true; })
                .orElse(false);
    }

    public CompletableFuture<Void> loadUsersFromDatabase() {
        return repository.fetchAllUsers()
                .thenAccept(users -> {
                    users.stream()
                            .filter(u -> u.getUid() != null)
                            .map(u -> connectedChannels.containsKey(u.getUid())
                                    ? u.withOnlineStatus(true) : u)
                            .forEach(u -> userCache.put(u.getUid(), u));
                    log.info("Loaded {} users into presence cache", userCache.size());
                });
    }

    public Optional<User> getCachedUser(String uid)    { return Optional.ofNullable(userCache.get(uid)); }
    public void           updateCache(User user)        { userCache.put(user.getUid(), user); }
    public int            connectedCount()              { return connectedChannels.size(); }
    public List<User>     getAllKnownUsers()             { return List.copyOf(userCache.values()); }
}
