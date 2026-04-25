package com.chatapp.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Immutable Room model representing a group chat room.
 *
 * Functional principles:
 *   - All fields final
 *   - Static factory methods
 *   - withXxx methods return new instances
 *   - Members stored as unmodifiable Map<uid, Role>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Room {

    public enum Role { ADMIN, MEMBER }

    private final String id;
    private final String name;
    private final String description;
    private final String createdBy;
    private final long   createdAt;
    private final Map<String, Role> members; // uid → Role

    @JsonCreator
    private Room(
            @JsonProperty("id")          String id,
            @JsonProperty("name")        String name,
            @JsonProperty("description") String description,
            @JsonProperty("createdBy")   String createdBy,
            @JsonProperty("createdAt")   long createdAt,
            @JsonProperty("members")     Map<String, Role> members
    ) {
        this.id          = id;
        this.name        = name;
        this.description = description;
        this.createdBy   = createdBy;
        this.createdAt   = createdAt;
        this.members     = members != null
                ? Collections.unmodifiableMap(new HashMap<>(members))
                : Map.of();
    }

    // ── Static factory methods ────────────────────────────────────────────────

    public static Room of(String name, String description, String creatorUid) {
        Map<String, Role> members = new HashMap<>();
        members.put(creatorUid, Role.ADMIN);
        return new Room(
                UUID.randomUUID().toString(),
                name.trim(), description.trim(),
                creatorUid,
                Instant.now().toEpochMilli(),
                members
        );
    }

    // ── Immutable update methods ───────────────────────────────────────────────

    /** Returns new Room with the given user added as MEMBER */
    public Room withMember(String uid) {
        Map<String, Role> updated = new HashMap<>(members);
        updated.put(uid, Role.MEMBER);
        return new Room(id, name, description, createdBy, createdAt, updated);
    }

    /** Returns new Room with the given user removed */
    public Room withoutMember(String uid) {
        Map<String, Role> updated = new HashMap<>(members);
        updated.remove(uid);
        return new Room(id, name, description, createdBy, createdAt, updated);
    }

    /** Returns new Room with the given user promoted to ADMIN */
    public Room withAdmin(String uid) {
        Map<String, Role> updated = new HashMap<>(members);
        updated.put(uid, Role.ADMIN);
        return new Room(id, name, description, createdBy, createdAt, updated);
    }

    // ── Query methods (pure) ─────────────────────────────────────────────────

    public boolean hasMember(String uid)  { return members.containsKey(uid); }
    public boolean isAdmin(String uid)    { return Role.ADMIN.equals(members.get(uid)); }
    public int     memberCount()          { return members.size(); }

    @JsonIgnore
    public List<String> getMemberUids() {
        return List.copyOf(members.keySet());
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getId()          { return id; }
    public String getName()        { return name; }
    public String getDescription() { return description; }
    public String getCreatedBy()   { return createdBy; }
    public long   getCreatedAt()   { return createdAt; }
    public Map<String, Role> getMembers() { return members; }

    @Override
    public String toString() {
        return String.format("Room{id=%s, name=%s, members=%d}", id, name, members.size());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Room r)) return false;
        return Objects.equals(id, r.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
