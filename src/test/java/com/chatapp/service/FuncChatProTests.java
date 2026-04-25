package com.chatapp.service;

import com.chatapp.model.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FuncChat Pro — Comprehensive Unit Test Suite (Phase 2).
 *
 * Tests cover all functional programming concepts:
 *   - Immutability (Message, User, Room)
 *   - Predicate composition and combinators
 *   - Function and UnaryOperator transforms
 *   - Optional chaining
 *   - Stream pipelines
 *   - Pure function determinism
 *
 * All tests are pure — no Firebase, no network, no S3.
 * 100% offline executable with: mvn test
 */
@DisplayName("FuncChat Pro — Phase 2 Functional Tests")
class FuncChatProTests {

    // ══════════════════════════════════════════════════════════════════════════
    // MESSAGE IMMUTABILITY
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Message immutability")
    class MessageImmutabilityTests {

        @Test
        @DisplayName("withStatus returns new instance, original unchanged")
        void testWithStatusImmutability() {
            Message original = Message.of("u1", "u2", "Hello");
            Message updated  = original.withStatus(Message.MessageStatus.DELIVERED);

            assertNotSame(original, updated);
            assertEquals(Message.MessageStatus.SENT,      original.getStatus());
            assertEquals(Message.MessageStatus.DELIVERED, updated.getStatus());
            assertEquals(original.getContent(),    updated.getContent());
            assertEquals(original.getSenderId(),   updated.getSenderId());
        }

        @Test
        @DisplayName("withReadBy returns new instance with uid added")
        void testWithReadByImmutability() {
            Message msg     = Message.forRoom("u1", "room1", "Hi team");
            Message withRead = msg.withReadBy("u2");

            assertNotSame(msg, withRead);
            assertFalse(msg.getReadBy().containsKey("u2"),     "Original must not contain u2");
            assertTrue(withRead.getReadBy().containsKey("u2"), "New instance must contain u2");
        }

        @Test
        @DisplayName("readBy map is unmodifiable")
        void testReadByUnmodifiable() {
            Message msg = Message.forRoom("u1", "room1", "Hi").withReadBy("u2");
            assertThrows(UnsupportedOperationException.class,
                    () -> msg.getReadBy().put("u3", true));
        }

        @Test
        @DisplayName("buildConversationId is symmetric and pure")
        void testConversationIdSymmetry() {
            String id1 = Message.buildConversationId("alice", "bob");
            String id2 = Message.buildConversationId("bob", "alice");
            assertEquals(id1, id2, "Must be symmetric");
            assertEquals(id1, Message.buildConversationId("alice", "bob"),
                    "Must be deterministic");
        }

        @Test
        @DisplayName("Group message has null receiverId and non-null roomId")
        void testGroupMessageFields() {
            Message msg = Message.forRoom("u1", "room-abc", "Team update");
            assertNull(msg.getReceiverId());
            assertEquals("room-abc", msg.getRoomId());
            assertTrue(msg.isGroupMessage());
        }

        @Test
        @DisplayName("P2P message has non-null receiverId and null roomId")
        void testP2PMessageFields() {
            Message msg = Message.of("u1", "u2", "Direct message");
            assertNotNull(msg.getReceiverId());
            assertNull(msg.getRoomId());
            assertFalse(msg.isGroupMessage());
        }

        @Test
        @DisplayName("File message has fileUrl and fileName in Optional")
        void testFileMessageOptionals() {
            Message msg = Message.fileMessage("u1", "room1",
                    "https://s3.example.com/file.pdf", "report.pdf", 1024L);
            assertTrue(msg.getFileUrl().isPresent());
            assertTrue(msg.getFileName().isPresent());
            assertEquals("report.pdf",                      msg.getFileName().get());
            assertEquals(Message.MessageType.FILE,           msg.getType());
            assertEquals(1024L,                              msg.getFileSize());
        }

        @Test
        @DisplayName("Bot message has senderId BOT and type BOT")
        void testBotMessage() {
            Message msg = Message.botMessage("room1", "[CI] Build passed", Message.MessageType.BOT);
            assertEquals("BOT",                    msg.getSenderId());
            assertEquals(Message.MessageType.BOT,   msg.getType());
            assertEquals("room1",                  msg.getRoomId());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MESSAGE SERVICE PREDICATES
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MessageService predicates")
    class MessagePredicateTests {

        @Test
        @DisplayName("HAS_CONTENT rejects blank and null")
        void testHasContent() {
            assertTrue(MessageService.HAS_CONTENT.test(Message.of("a", "b", "Hello")));
            assertFalse(MessageService.HAS_CONTENT.test(Message.of("a", "b", "   ")));
        }

        @Test
        @DisplayName("NOT_TOO_LONG rejects messages over 2000 chars")
        void testNotTooLong() {
            assertTrue(MessageService.NOT_TOO_LONG.test(Message.of("a", "b", "A".repeat(2000))));
            assertFalse(MessageService.NOT_TOO_LONG.test(Message.of("a", "b", "A".repeat(2001))));
        }

        @Test
        @DisplayName("IS_TEXT accepts TEXT and BOT, rejects FILE and SYSTEM")
        void testIsText() {
            assertTrue(MessageService.IS_TEXT.test(Message.of("a", "b", "Hi")));
            assertTrue(MessageService.IS_TEXT.test(
                    Message.botMessage("r1", "alert", Message.MessageType.BOT)));
            assertFalse(MessageService.IS_TEXT.test(
                    Message.fileMessage("a", "r1", "url", "file.pdf", 100)));
        }

        @Test
        @DisplayName("IS_FILE accepts FILE type only")
        void testIsFile() {
            assertTrue(MessageService.IS_FILE.test(
                    Message.fileMessage("a", "r1", "url", "img.png", 200)));
            assertFalse(MessageService.IS_FILE.test(Message.of("a", "b", "text")));
        }

        @Test
        @DisplayName("VALID_TEXT_MESSAGE is composed predicate — all conditions must pass")
        void testValidTextMessageComposition() {
            Message good    = Message.of("a", "b", "Valid message");
            Message blank   = Message.of("a", "b", "  ");
            Message tooLong = Message.of("a", "b", "X".repeat(2001));
            Message file    = Message.fileMessage("a", "r1", "u", "f.pdf", 0);

            assertTrue(MessageService.VALID_TEXT_MESSAGE.test(good));
            assertFalse(MessageService.VALID_TEXT_MESSAGE.test(blank));
            assertFalse(MessageService.VALID_TEXT_MESSAGE.test(tooLong));
            assertFalse(MessageService.VALID_TEXT_MESSAGE.test(file));
        }

        @Test
        @DisplayName("VALID_GROUP_MESSAGE accepts text or file")
        void testValidGroupMessage() {
            assertTrue(MessageService.VALID_GROUP_MESSAGE.test(
                    Message.forRoom("a", "r1", "Hello team")));
            assertTrue(MessageService.VALID_GROUP_MESSAGE.test(
                    Message.fileMessage("a", "r1", "url", "doc.pdf", 100)));
            assertFalse(MessageService.VALID_GROUP_MESSAGE.test(
                    Message.forRoom("a", "r1", "")));
        }

        @Test
        @DisplayName("Predicate .negate() works correctly")
        void testPredicateNegate() {
            Predicate<Message> notTooLong = MessageService.NOT_TOO_LONG;
            Predicate<Message> tooLong    = notTooLong.negate();

            assertFalse(tooLong.test(Message.of("a", "b", "short")));
            assertTrue(tooLong.test(Message.of("a", "b", "X".repeat(2001))));
        }

        @Test
        @DisplayName("Predicate .and() composition chains correctly")
        void testAndComposition() {
            Predicate<Message> combined =
                    MessageService.HAS_CONTENT.and(MessageService.NOT_TOO_LONG);

            assertTrue(combined.test(Message.of("a", "b", "good")));
            assertFalse(combined.test(Message.of("a", "b", "")));
            assertFalse(combined.test(Message.of("a", "b", "X".repeat(2001))));
        }

        @Test
        @DisplayName("Predicate .or() composition works")
        void testOrComposition() {
            Predicate<Message> textOrFile =
                    MessageService.IS_TEXT.or(MessageService.IS_FILE);

            assertTrue(textOrFile.test(Message.of("a", "b", "hi")));
            assertTrue(textOrFile.test(Message.fileMessage("a", "r1", "u", "f.pdf", 0)));
            assertFalse(textOrFile.test(
                    Message.system("a", "b", "sys", Message.MessageType.SYSTEM)));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MESSAGE TRANSFORMS
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Message functional transforms")
    class MessageTransformTests {

        @Test
        @DisplayName("SANITISE trims whitespace — returns new instance")
        void testSanitise() {
            Message raw       = Message.of("a", "b", "  Hello World  ");
            Message sanitised = MessageService.SANITISE.apply(raw);

            assertEquals("Hello World", sanitised.getContent());
            assertNotSame(raw, sanitised);
            assertEquals("  Hello World  ", raw.getContent(), "Original must be unchanged");
        }

        @Test
        @DisplayName("MARK_DELIVERED is pure — returns new instance")
        void testMarkDelivered() {
            Message sent      = Message.of("a", "b", "Hi");
            Message delivered = MessageService.MARK_DELIVERED.apply(sent);

            assertEquals(Message.MessageStatus.SENT,      sent.getStatus());
            assertEquals(Message.MessageStatus.DELIVERED, delivered.getStatus());
            assertNotSame(sent, delivered);
        }

        @Test
        @DisplayName("MARK_READ is pure — returns new instance")
        void testMarkRead() {
            Message msg  = Message.of("a", "b", "Hi")
                    .withStatus(Message.MessageStatus.DELIVERED);
            Message read = MessageService.MARK_READ.apply(msg);

            assertEquals(Message.MessageStatus.DELIVERED, msg.getStatus());
            assertEquals(Message.MessageStatus.READ,      read.getStatus());
        }

        @Test
        @DisplayName("Function.andThen() chains SANITISE then MARK_DELIVERED")
        void testFunctionChaining() {
            Message raw = Message.of("a", "b", "  Hi there  ");
            Message result = MessageService.SANITISE
                    .andThen(MessageService.MARK_DELIVERED)
                    .apply(raw);

            assertEquals("Hi there",                      result.getContent());
            assertEquals(Message.MessageStatus.DELIVERED, result.getStatus());
        }

        @Test
        @DisplayName("Stream pipeline: filter valid, sanitise, sort by timestamp")
        void testStreamPipeline() {
            List<Message> raw = List.of(
                    Message.of("a", "b", "  Third  "),
                    Message.of("a", "b", ""),
                    Message.of("a", "b", "  First  "),
                    Message.of("a", "b", "X".repeat(2001))
            );

            List<Message> processed = raw.stream()
                    .filter(MessageService.VALID_TEXT_MESSAGE)
                    .map(MessageService.SANITISE)
                    .sorted(Comparator.comparingLong(Message::getTimestamp))
                    .collect(Collectors.toList());

            assertEquals(2, processed.size(), "Only 2 messages are valid");
            assertTrue(processed.stream().noneMatch(m ->
                    m.getContent().startsWith(" ") || m.getContent().endsWith(" ")),
                    "All content must be trimmed");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ROOM IMMUTABILITY
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Room immutability")
    class RoomImmutabilityTests {

        @Test
        @DisplayName("withMember returns new instance, original unchanged")
        void testWithMemberImmutability() {
            Room original = Room.of("Engineering", "Dev team", "uid-alice");
            Room updated  = original.withMember("uid-bob");

            assertNotSame(original, updated);
            assertFalse(original.hasMember("uid-bob"), "Original must not have bob");
            assertTrue(updated.hasMember("uid-bob"),   "Updated must have bob");
            assertEquals(1, original.memberCount());
            assertEquals(2, updated.memberCount());
        }

        @Test
        @DisplayName("withoutMember returns new instance with user removed")
        void testWithoutMember() {
            Room room    = Room.of("Team", "", "alice").withMember("bob");
            Room removed = room.withoutMember("bob");

            assertTrue(room.hasMember("bob"),    "Room must have bob");
            assertFalse(removed.hasMember("bob"),"Removed room must not have bob");
        }

        @Test
        @DisplayName("withAdmin promotes user to ADMIN role")
        void testWithAdmin() {
            Room room     = Room.of("Team", "", "alice").withMember("bob");
            Room promoted = room.withAdmin("bob");

            assertFalse(room.isAdmin("bob"),     "Bob must not be admin before promote");
            assertTrue(promoted.isAdmin("bob"),   "Bob must be admin after promote");
            assertNotSame(room, promoted);
        }

        @Test
        @DisplayName("Creator is automatically ADMIN")
        void testCreatorIsAdmin() {
            Room room = Room.of("Dev", "desc", "alice");
            assertTrue(room.isAdmin("alice"), "Creator must be ADMIN");
            assertTrue(room.hasMember("alice"));
        }

        @Test
        @DisplayName("members map is unmodifiable")
        void testMembersMapUnmodifiable() {
            Room room = Room.of("Team", "", "alice");
            assertThrows(UnsupportedOperationException.class,
                    () -> room.getMembers().put("hacker", Room.Role.ADMIN));
        }

        @Test
        @DisplayName("getMemberUids returns all member UIDs")
        void testGetMemberUids() {
            Room room = Room.of("Team", "", "alice")
                    .withMember("bob")
                    .withMember("charlie");
            List<String> uids = room.getMemberUids();
            assertEquals(3, uids.size());
            assertTrue(uids.contains("alice"));
            assertTrue(uids.contains("bob"));
            assertTrue(uids.contains("charlie"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GROUP ROOM SERVICE PREDICATES
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GroupRoomService predicates")
    class GroupRoomPredicateTests {

        @Test
        @DisplayName("IS_MEMBER detects membership correctly")
        void testIsMember() {
            Map<String, Room.Role> members = Map.of(
                    "alice", Room.Role.ADMIN,
                    "bob",   Room.Role.MEMBER);

            assertTrue(GroupRoomService.IS_MEMBER("alice").test(members));
            assertTrue(GroupRoomService.IS_MEMBER("bob").test(members));
            assertFalse(GroupRoomService.IS_MEMBER("charlie").test(members));
        }

        @Test
        @DisplayName("IS_ADMIN detects admin role correctly")
        void testIsAdmin() {
            Map<String, Room.Role> members = Map.of(
                    "alice", Room.Role.ADMIN,
                    "bob",   Room.Role.MEMBER);

            assertTrue(GroupRoomService.IS_ADMIN("alice").test(members));
            assertFalse(GroupRoomService.IS_ADMIN("bob").test(members));
            assertFalse(GroupRoomService.IS_ADMIN("charlie").test(members));
        }

        @Test
        @DisplayName("HAS_MEMBERS predicate detects non-empty rooms")
        void testHasMembers() {
            Room empty   = Room.of("Empty", "", "u1").withoutMember("u1");
            Room nonEmpty = Room.of("Team", "", "u1");

            assertTrue(GroupRoomService.HAS_MEMBERS.test(nonEmpty));
            assertFalse(GroupRoomService.HAS_MEMBERS.test(empty));
        }

        @Test
        @DisplayName("VALID_ROOM_NAME rejects blank and too-short names")
        void testValidRoomName() {
            assertTrue(GroupRoomService.VALID_ROOM_NAME.test("Engineering"));
            assertTrue(GroupRoomService.VALID_ROOM_NAME.test("AB"));
            assertFalse(GroupRoomService.VALID_ROOM_NAME.test("A"));
            assertFalse(GroupRoomService.VALID_ROOM_NAME.test(""));
            assertFalse(GroupRoomService.VALID_ROOM_NAME.test(null));
            assertFalse(GroupRoomService.VALID_ROOM_NAME.test("X".repeat(65)));
        }

        @Test
        @DisplayName("memberOf curried predicate works as Predicate<Room>")
        void testMemberOfCurried() {
            Room room = Room.of("Team", "", "alice").withMember("bob");

            Predicate<Room> aliceCheck   = RoleAccessService.memberOf("alice");
            Predicate<Room> charlieCheck = RoleAccessService.memberOf("charlie");

            assertTrue(aliceCheck.test(room));
            assertFalse(charlieCheck.test(room));
        }

        @Test
        @DisplayName("adminOf curried predicate works as Predicate<Room>")
        void testAdminOfCurried() {
            Room room = Room.of("Team", "", "alice").withMember("bob");

            assertTrue(RoleAccessService.adminOf("alice").test(room));
            assertFalse(RoleAccessService.adminOf("bob").test(room));
        }

        @Test
        @DisplayName("getRoomsForUser filters by membership using stream + predicate")
        void testGetRoomsForUserStream() {
            // Simulate the stream logic used in GroupRoomService.getRoomsForUser()
            List<Room> allRooms = List.of(
                    Room.of("Room A", "", "alice"),
                    Room.of("Room B", "", "bob"),
                    Room.of("Room C", "", "alice").withMember("charlie")
            );

            List<Room> aliceRooms = allRooms.stream()
                    .filter(room -> GroupRoomService.IS_MEMBER("alice").test(room.getMembers()))
                    .collect(Collectors.toList());

            assertEquals(2, aliceRooms.size());
            assertTrue(aliceRooms.stream().allMatch(r -> r.hasMember("alice")));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FILE SHARE SERVICE PREDICATES
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("FileShareService predicates")
    class FileSharePredicateTests {

        @Test
        @DisplayName("WITHIN_SIZE_LIMIT accepts ≤5 MB, rejects >5 MB; 0 means unknown (allowed)")
        void testSizeLimit() {
            assertTrue(FileShareService.WITHIN_SIZE_LIMIT.test(1L));
            assertTrue(FileShareService.WITHIN_SIZE_LIMIT.test(5 * 1024 * 1024L));
            assertFalse(FileShareService.WITHIN_SIZE_LIMIT.test(5 * 1024 * 1024L + 1));
            // 0 = client did not send fileSize; pre-check is skipped, actual byte-array
            // size is validated inside upload() — so 0 must be accepted here
            assertTrue(FileShareService.WITHIN_SIZE_LIMIT.test(0L));
            assertFalse(FileShareService.WITHIN_SIZE_LIMIT.test(-1L));
        }

        @Test
        @DisplayName("ALLOWED_TYPE permits whitelisted extensions")
        void testAllowedType() {
            assertTrue(FileShareService.ALLOWED_TYPE.test("photo.jpg"));
            assertTrue(FileShareService.ALLOWED_TYPE.test("Photo.JPEG"));
            assertTrue(FileShareService.ALLOWED_TYPE.test("report.pdf"));
            assertTrue(FileShareService.ALLOWED_TYPE.test("data.zip"));
            assertTrue(FileShareService.ALLOWED_TYPE.test("notes.txt"));
            assertTrue(FileShareService.ALLOWED_TYPE.test("image.png"));
        }

        @Test
        @DisplayName("ALLOWED_TYPE blocks dangerous extensions")
        void testBlockedTypes() {
            assertFalse(FileShareService.ALLOWED_TYPE.test("virus.exe"));
            assertFalse(FileShareService.ALLOWED_TYPE.test("script.sh"));
            assertFalse(FileShareService.ALLOWED_TYPE.test("hack.bat"));
            assertFalse(FileShareService.ALLOWED_TYPE.test("malware.dll"));
            assertFalse(FileShareService.ALLOWED_TYPE.test("noextension"));
            assertFalse(FileShareService.ALLOWED_TYPE.test(null));
        }

        @Test
        @DisplayName("SAFE_FILENAME blocks path traversal and null bytes")
        void testSafeFilename() {
            assertTrue(FileShareService.SAFE_FILENAME.test("report.pdf"));
            assertFalse(FileShareService.SAFE_FILENAME.test("../etc/passwd"));
            assertFalse(FileShareService.SAFE_FILENAME.test("folder/file.pdf"));
            assertFalse(FileShareService.SAFE_FILENAME.test("folder\\file.pdf"));
            assertFalse(FileShareService.SAFE_FILENAME.test(null));
        }

        @Test
        @DisplayName("VALID_FILENAME is composed predicate (ALLOWED_TYPE.and(SAFE_FILENAME))")
        void testValidFilenameComposition() {
            assertTrue(FileShareService.VALID_FILENAME.test("report.pdf"));
            assertTrue(FileShareService.VALID_FILENAME.test("photo.png"));
            assertFalse(FileShareService.VALID_FILENAME.test("../evil.pdf"));
            assertFalse(FileShareService.VALID_FILENAME.test("virus.exe"));
            assertFalse(FileShareService.VALID_FILENAME.test("../evil.exe"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AUTH SERVICE
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AuthService predicates and pure functions")
    class AuthServiceTests {

        @Test
        @DisplayName("VALID_EMAIL accepts well-formed emails")
        void testValidEmail() {
            assertTrue(AuthService.VALID_EMAIL.test("test@example.com"));
            assertTrue(AuthService.VALID_EMAIL.test("user.name+tag@uni.edu.in"));
            assertFalse(AuthService.VALID_EMAIL.test("notanemail"));
            assertFalse(AuthService.VALID_EMAIL.test("@nodomain"));
            assertFalse(AuthService.VALID_EMAIL.test(null));
            assertFalse(AuthService.VALID_EMAIL.test(""));
        }

        @Test
        @DisplayName("VALID_PASSWORD requires 6+ characters")
        void testValidPassword() {
            assertTrue(AuthService.VALID_PASSWORD.test("password123"));
            assertTrue(AuthService.VALID_PASSWORD.test("123456"));
            assertFalse(AuthService.VALID_PASSWORD.test("12345"));
            assertFalse(AuthService.VALID_PASSWORD.test(""));
            assertFalse(AuthService.VALID_PASSWORD.test(null));
        }

        @Test
        @DisplayName("NORMALISE_EMAIL is deterministic pure function")
        void testNormaliseEmail() {
            assertEquals("test@example.com",
                    AuthService.NORMALISE_EMAIL.apply("  TEST@Example.COM  "));
            assertEquals("user@uni.edu",
                    AuthService.NORMALISE_EMAIL.apply("USER@UNI.EDU"));
            // Same input → same output (determinism)
            String input = "  Hello@World.COM  ";
            assertEquals(AuthService.NORMALISE_EMAIL.apply(input),
                    AuthService.NORMALISE_EMAIL.apply(input));
        }

        @Test
        @DisplayName("DEFAULT_DISPLAY_NAME derives name from email prefix")
        void testDefaultDisplayName() {
            assertEquals("johndoe", AuthService.DEFAULT_DISPLAY_NAME.apply("johndoe@gmail.com"));
            assertEquals("alice",   AuthService.DEFAULT_DISPLAY_NAME.apply("alice@uni.edu"));
        }

        @Test
        @DisplayName("NORMALISE_EMAIL then DEFAULT_DISPLAY_NAME chains via Function.andThen()")
        void testFunctionChain() {
            String result = AuthService.NORMALISE_EMAIL
                    .andThen(AuthService.DEFAULT_DISPLAY_NAME)
                    .apply("  JOHN.DOE@company.COM  ");
            assertEquals("john.doe", result);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // USER IMMUTABILITY
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("User immutability")
    class UserImmutabilityTests {

        @Test
        @DisplayName("withOnlineStatus returns new User, original unchanged")
        void testWithOnlineStatus() {
            User original = User.of("u1", "a@b.com", "Alice");
            User online   = original.withOnlineStatus(true);

            assertNotSame(original, online);
            assertFalse(original.isOnline());
            assertTrue(online.isOnline());
            assertEquals(original.getUid(), online.getUid());
        }

        @Test
        @DisplayName("IS_ONLINE predicate works on User")
        void testIsOnlinePredicate() {
            User online  = User.of("u1", "a@b.com", "Alice").withOnlineStatus(true);
            User offline = User.of("u2", "b@c.com", "Bob");

            assertTrue(PresenceService.IS_ONLINE.test(online));
            assertFalse(PresenceService.IS_ONLINE.test(offline));
            assertTrue(PresenceService.IS_OFFLINE.test(offline));
            assertFalse(PresenceService.IS_OFFLINE.test(online));
        }

        @Test
        @DisplayName("IS_OFFLINE is IS_ONLINE.negate()")
        void testIsOfflineNegate() {
            User u = User.of("u1", "a@b.com", "X").withOnlineStatus(true);
            assertEquals(PresenceService.IS_ONLINE.negate().test(u),
                    PresenceService.IS_OFFLINE.test(u));
        }

        @Test
        @DisplayName("avatarUrl Optional is empty by default")
        void testAvatarUrlEmpty() {
            User user = User.of("u1", "a@b.com", "Test");
            assertTrue(user.getAvatarUrl().isEmpty());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // OPTIONAL CHAINING
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Optional chaining and null-safety")
    class OptionalTests {

        @Test
        @DisplayName("Message.getConversationId returns non-empty Optional for P2P")
        void testP2PConversationId() {
            Message msg = Message.of("a", "b", "Hi");
            assertTrue(msg.getConversationId().isPresent());
            assertFalse(msg.getConversationId().get().isBlank());
        }

        @Test
        @DisplayName("Group message conversationId is empty Optional")
        void testGroupConversationIdEmpty() {
            Message msg = Message.forRoom("u1", "room1", "Hi");
            assertFalse(msg.getConversationId().isPresent());
        }

        @Test
        @DisplayName("fileUrl Optional is empty for text messages")
        void testFileUrlEmpty() {
            Message text = Message.of("a", "b", "hello");
            assertTrue(text.getFileUrl().isEmpty());
            assertTrue(text.getFileName().isEmpty());
        }

        @Test
        @DisplayName("Optional.map chain does not throw on empty")
        void testOptionalMapChainEmpty() {
            Optional<String> result = Optional.<String>empty()
                    .map(String::toUpperCase)
                    .filter(s -> s.length() > 3)
                    .map(s -> s + "_done");
            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("Optional.flatMap chain works for nested optionals")
        void testFlatMap() {
            Optional<Message> optMsg = Optional.of(
                    Message.fileMessage("u1", "r1", "https://s3.example.com/file.pdf",
                            "file.pdf", 512L));
            Optional<String> url = optMsg.flatMap(Message::getFileUrl);
            assertTrue(url.isPresent());
            assertTrue(url.get().startsWith("https://"));
        }

        @Test
        @DisplayName("Optional.orElse provides fallback for empty")
        void testOrElse() {
            Message text = Message.of("u1", "u2", "hi");
            String url = text.getFileUrl().orElse("no-file");
            assertEquals("no-file", url);
        }

        @Test
        @DisplayName("Optional.ifPresent only executes on non-empty")
        void testIfPresent() {
            List<String> results = new ArrayList<>();
            Optional.of(Message.fileMessage("u1", "r1", "https://x.com/f", "f.zip", 100))
                    .flatMap(Message::getFileUrl)
                    .ifPresent(results::add);
            assertEquals(1, results.size());

            Optional.<Message>empty()
                    .flatMap(Message::getFileUrl)
                    .ifPresent(results::add);
            assertEquals(1, results.size(), "Empty optional must not add to list");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STREAM PIPELINES
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Stream API pipelines")
    class StreamPipelineTests {

        @Test
        @DisplayName("Stream filter + map + collect on messages")
        void testMessageStreamPipeline() {
            List<Message> messages = List.of(
                    Message.of("alice", "bob", "Hello Bob"),
                    Message.of("bob", "alice", "Hi Alice"),
                    Message.of("alice", "bob", "  "),
                    Message.of("alice", "bob", "Meeting at 3pm")
            );

            List<String> fromAlice = messages.stream()
                    .filter(MessageService.HAS_CONTENT)
                    .filter(m -> "alice".equals(m.getSenderId()))
                    .map(MessageService.SANITISE)
                    .map(Message::getContent)
                    .collect(Collectors.toList());

            assertEquals(2, fromAlice.size());
            assertTrue(fromAlice.contains("Hello Bob"));
            assertTrue(fromAlice.contains("Meeting at 3pm"));
        }

        @Test
        @DisplayName("Stream search simulation: filter by keyword")
        void testSearchStreamPipeline() {
            List<Message> messages = List.of(
                    Message.forRoom("u1", "r1", "Team standup at 9am"),
                    Message.forRoom("u2", "r1", "Please review the PR"),
                    Message.forRoom("u1", "r1", "Standup cancelled today"),
                    Message.forRoom("u3", "r1", "Build is green")
            );

            String query = "standup";
            List<Message> results = messages.stream()
                    .filter(m -> m.getContent().toLowerCase().contains(query.toLowerCase()))
                    .sorted(Comparator.comparingLong(Message::getTimestamp).reversed())
                    .collect(Collectors.toList());

            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(
                    m -> m.getContent().toLowerCase().contains("standup")));
        }

        @Test
        @DisplayName("Stream room membership filter")
        void testRoomMembershipStream() {
            List<Room> rooms = List.of(
                    Room.of("Dev",   "", "alice").withMember("bob"),
                    Room.of("Design","", "carol"),
                    Room.of("All",   "", "alice").withMember("carol").withMember("bob")
            );

            List<Room> bobRooms = rooms.stream()
                    .filter(r -> GroupRoomService.IS_MEMBER("bob").test(r.getMembers()))
                    .collect(Collectors.toList());

            assertEquals(2, bobRooms.size());
            assertTrue(bobRooms.stream().allMatch(r -> r.hasMember("bob")));
        }

        @Test
        @DisplayName("Stream online user filter using IS_ONLINE predicate")
        void testOnlineUserStream() {
            List<User> users = List.of(
                    User.of("u1", "a@b.com", "Alice").withOnlineStatus(true),
                    User.of("u2", "b@c.com", "Bob"),
                    User.of("u3", "c@d.com", "Carol").withOnlineStatus(true),
                    User.of("u4", "d@e.com", "Dave")
            );

            long onlineCount = users.stream()
                    .filter(PresenceService.IS_ONLINE)
                    .count();
            assertEquals(2, onlineCount);

            List<String> onlineNames = users.stream()
                    .filter(PresenceService.IS_ONLINE)
                    .map(User::getDisplayName)
                    .sorted()
                    .collect(Collectors.toList());
            assertEquals(List.of("Alice", "Carol"), onlineNames);
        }

        @Test
        @DisplayName("Stream groupingBy rooms per user")
        void testGroupingByStream() {
            List<Room> rooms = List.of(
                    Room.of("Dev",   "", "alice"),
                    Room.of("Design","", "alice").withMember("bob"),
                    Room.of("All",   "", "carol").withMember("alice")
            );

            Map<String, Long> roomsPerUser = rooms.stream()
                    .flatMap(room -> room.getMemberUids().stream()
                            .map(uid -> Map.entry(uid, room)))
                    .collect(Collectors.groupingBy(
                            Map.Entry::getKey, Collectors.counting()));

            assertEquals(3L, roomsPerUser.get("alice"));
            assertEquals(1L, roomsPerUser.get("bob"));
            assertEquals(1L, roomsPerUser.get("carol"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HIGHER-ORDER FUNCTIONS
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Higher-order functions")
    class HigherOrderFunctionTests {

        @Test
        @DisplayName("Function passed as parameter to map()")
        void testFunctionAsParameter() {
            Function<Message, String> extractContent = Message::getContent;

            List<String> contents = List.of(
                    Message.of("a", "b", "First"),
                    Message.of("a", "b", "Second")
            ).stream().map(extractContent).collect(Collectors.toList());

            assertEquals(List.of("First", "Second"), contents);
        }

        @Test
        @DisplayName("Predicate passed as parameter to filter()")
        void testPredicateAsParameter() {
            Predicate<Message> fromAlice = m -> "alice".equals(m.getSenderId());

            List<Message> messages = List.of(
                    Message.of("alice", "bob", "Hello"),
                    Message.of("bob",   "alice", "Hi"),
                    Message.of("alice", "carol", "Hey")
            );

            long count = messages.stream().filter(fromAlice).count();
            assertEquals(2, count);
        }

        @Test
        @DisplayName("Consumer used for side-effect isolation (collect into list)")
        void testConsumerSideEffect() {
            List<String> received = new ArrayList<>();
            java.util.function.Consumer<Message> collector =
                    msg -> received.add(msg.getContent());

            List.of(
                    Message.of("a", "b", "One"),
                    Message.of("a", "b", "Two")
            ).forEach(collector);

            assertEquals(List.of("One", "Two"), received);
        }

        @Test
        @DisplayName("Supplier provides default value lazily")
        void testSupplierLazy() {
            java.util.function.Supplier<Message> defaultMsg =
                    () -> Message.botMessage("global", "No messages yet",
                            Message.MessageType.BOT);

            List<Message> empty = List.of();
            Message result = empty.stream().findFirst().orElseGet(defaultMsg);

            assertEquals("BOT", result.getSenderId());
            assertEquals("No messages yet", result.getContent());
        }

        @Test
        @DisplayName("BiPredicate for role checks")
        void testBiPredicate() {
            java.util.function.BiPredicate<String, Room> canManage =
                    (uid, room) -> room.isAdmin(uid);

            Room room = Room.of("Team", "", "alice").withMember("bob");
            assertTrue(canManage.test("alice", room));
            assertFalse(canManage.test("bob",   room));
            assertFalse(canManage.test("carol",  room));
        }
    }
}
