package com.chatapp.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable User model.
 *
 * Functional principles: all fields final, static factory methods,
 * withXxx returns new instances, Optional for nullable fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class User {

    private final String uid;
    private final String email;
    private final String displayName;
    private final boolean online;
    private final long lastSeen;
    private final Optional<String> avatarUrl;

    @JsonCreator
    private User(
            @JsonProperty("uid")         String uid,
            @JsonProperty("email")       String email,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("online")      boolean online,
            @JsonProperty("lastSeen")    long lastSeen,
            @JsonProperty("avatarUrl")   String avatarUrl
    ) {
        this.uid         = uid;
        this.email       = email;
        this.displayName = displayName;
        this.online      = online;
        this.lastSeen    = lastSeen;
        this.avatarUrl   = Optional.ofNullable(avatarUrl);
    }

    public static User of(String uid, String email, String displayName) {
        return new User(uid, email, displayName, false, System.currentTimeMillis(), null);
    }

    public User withOnlineStatus(boolean isOnline) {
        return new User(uid, email, displayName, isOnline,
                System.currentTimeMillis(), avatarUrl.orElse(null));
    }

    public User withDisplayName(String newName) {
        return new User(uid, email, newName, online, lastSeen, avatarUrl.orElse(null));
    }

    public String  getUid()          { return uid; }
    public String  getEmail()        { return email; }
    public String  getDisplayName()  { return displayName; }
    public boolean isOnline()        { return online; }
    public long    getLastSeen()     { return lastSeen; }
    public Optional<String> getAvatarUrl() { return avatarUrl; }

    @JsonProperty("avatarUrl")
    public String getAvatarUrlRaw()  { return avatarUrl.orElse(null); }

    @Override
    public String toString() {
        return String.format("User{%s, %s, online=%s}", uid, displayName, online);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User u)) return false;
        return Objects.equals(uid, u.uid);
    }

    @Override
    public int hashCode() { return Objects.hash(uid); }
}
