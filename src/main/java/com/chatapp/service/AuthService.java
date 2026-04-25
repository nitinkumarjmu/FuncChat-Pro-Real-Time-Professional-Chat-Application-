package com.chatapp.service;

import com.chatapp.model.User;
import com.chatapp.repository.FirebaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Authentication service using functional style.
 * Unchanged from Phase 1 — all FP patterns preserved.
 *
 * Functional principles:
 *   - Predicate<String> validators (composable, reusable)
 *   - Function<String,String> pure transformations
 *   - Optional<User> for null-safe session lookup
 *   - CompletableFuture for async login/register
 */
public final class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // ── Reusable validation predicates ────────────────────────────────────────
    public static final Predicate<String> VALID_EMAIL =
            email -> email != null && email.contains("@")
                    && email.contains(".") && email.length() >= 5;

    public static final Predicate<String> VALID_PASSWORD =
            pwd -> pwd != null && pwd.length() >= 6;

    public static final Predicate<String> VALID_DISPLAY_NAME =
            name -> name != null && !name.isBlank() && name.length() >= 2;

    // ── Pure transformation functions ─────────────────────────────────────────
    public static final Function<String, String> NORMALISE_EMAIL =
            email -> email.toLowerCase().trim();

    public static final Function<String, String> DEFAULT_DISPLAY_NAME =
            email -> email.split("@")[0];

    // ── State ─────────────────────────────────────────────────────────────────
    private final Map<String, User> activeSessions = new ConcurrentHashMap<>();
    private final FirebaseRepository repository;

    public AuthService(FirebaseRepository repository) {
        this.repository = repository;
    }

    public CompletableFuture<User> register(String email, String password, String displayName) {
        String normEmail = NORMALISE_EMAIL.apply(email);
        String name      = (displayName == null || displayName.isBlank())
                ? DEFAULT_DISPLAY_NAME.apply(normEmail) : displayName;

        if (!VALID_EMAIL.test(normEmail))    return fail("Invalid email format");
        if (!VALID_PASSWORD.test(password))  return fail("Password must be 6+ characters");
        if (!VALID_DISPLAY_NAME.test(name))  return fail("Display name too short");
        
        if (com.chatapp.config.AppConfig.get().isMockAuth()) {
            User mockUser = User.of("mock-" + normEmail.hashCode(), normEmail, name);
            return CompletableFuture.completedFuture(mockUser)
                    .thenApply(user -> { log.info("Registered (MOCK): {}", user.getUid()); return user; });
        }

        return repository.registerUser(normEmail, password, name)
                .thenApply(user -> { log.info("Registered: {}", user.getUid()); return user; });
    }

    public CompletableFuture<User> login(String email, String password) {
        String normEmail = NORMALISE_EMAIL.apply(email);
        if (!VALID_EMAIL.test(normEmail))   return fail("Invalid email");
        if (!VALID_PASSWORD.test(password)) return fail("Password required");

        if (com.chatapp.config.AppConfig.get().isMockAuth()) {
            User mockUser = User.of("mock-" + normEmail.hashCode(), normEmail, DEFAULT_DISPLAY_NAME.apply(normEmail));
            return CompletableFuture.completedFuture(mockUser)
                    .thenApply(user -> { log.info("Login (MOCK): {}", user.getUid()); return user; });
        }

        return repository.loginUser(normEmail, password)
                .thenApply(user -> { log.info("Login: {}", user.getUid()); return user; });
    }

    public void          storeSession(String channelId, User user) { activeSessions.put(channelId, user); }
    public Optional<User> getSession(String channelId)             { return Optional.ofNullable(activeSessions.get(channelId)); }
    public Optional<User> removeSession(String channelId)          { return Optional.ofNullable(activeSessions.remove(channelId)); }
    public boolean        isAuthenticated(String channelId)        { return activeSessions.containsKey(channelId); }

    private <T> CompletableFuture<T> fail(String msg) {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.completeExceptionally(new IllegalArgumentException(msg));
        return f;
    }
}
