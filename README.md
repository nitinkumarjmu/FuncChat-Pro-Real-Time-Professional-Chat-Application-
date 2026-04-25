# FuncChat Pro — Phase 2 Enhancement
### Functional Programming in Java | Course 23CSH-312

---

## What's new in Phase 2

| Feature | Phase 1 | Phase 2 |
|---|---|---|
| P2P messaging | Yes | Yes (unchanged) |
| Group chat rooms | No | Yes |
| Typing indicators | No | Yes (debounced, auto-stop) |
| File sharing | No | Yes (S3, 5 MB, pre-signed URLs) |
| Message search | No | Yes (full-text, server-side) |
| Admin controls | No | Yes (remove, promote) |
| Bot/webhook connector | No | Yes (HTTP POST → room) |
| Online/offline presence | Yes | Yes (enhanced for rooms) |
| Message history | Yes | Yes (P2P + group) |

---

## Tech stack

| Layer | Technology | Version |
|---|---|---|
| WebSocket server | Netty | 4.1.100 |
| File storage | AWS S3 SDK v2 | 2.25.0 |
| Firebase Auth + DB | REST via OkHttp | 4.11.0 |
| JSON | Jackson | 2.15.3 |
| Logging | Logback + SLF4J | 2.0.9 |
| Testing | JUnit 5 | 5.10.0 |
| Build | Maven | 3.8+ |
| UI | Java Swing | JDK 17 |

---

## Project structure

```
FuncChatPro/
├── Dockerfile
├── .env.template              ← Copy to .env
├── .gitignore
├── .github/workflows/
│   └── deploy.yml             ← GitHub Actions CI/CD
├── pom.xml
└── src/
    ├── main/java/com/chatapp/
    │   ├── ChatServer.java              ← Server entry point
    │   ├── config/AppConfig.java        ← Immutable config
    │   ├── model/
    │   │   ├── Message.java             ← Immutable (Phase 2: file, readBy, roomId)
    │   │   ├── Room.java                ← NEW — immutable room with roles
    │   │   ├── User.java                ← Immutable user
    │   │   └── ChatFrame.java           ← WebSocket protocol (Phase 2 actions)
    │   ├── repository/
    │   │   └── FirebaseRepository.java  ← All DB ops as CompletableFuture
    │   ├── service/
    │   │   ├── AuthService.java         ← Predicate validators, session store
    │   │   ├── MessageService.java      ← P2P + group pipelines
    │   │   ├── PresenceService.java     ← Online/offline + room broadcasting
    │   │   ├── GroupRoomService.java    ← NEW — room lifecycle, member management
    │   │   ├── TypingService.java       ← NEW — debounced typing indicators
    │   │   ├── FileShareService.java    ← NEW — S3 upload + pre-signed URLs
    │   │   └── RoleAccessService.java   ← NEW — BiPredicate permission checks
    │   ├── handler/
    │   │   └── WebSocketFrameHandler.java ← Dispatch table (13 actions)
    │   ├── webhook/
    │   │   └── BotWebhookServer.java    ← NEW — HTTP bot integration endpoint
    │   └── ui/
    │       └── ChatClientUI.java        ← Enhanced Swing client
    ├── test/java/com/chatapp/service/
    │   └── FuncChatProTests.java        ← 40+ JUnit 5 tests
    └── resources/
        └── logback.xml
```

---

## Functional programming concepts (CO mapping)

| FP Concept | Where | CO |
|---|---|---|
| Immutability | Message, Room, User — all fields final | CO1 |
| Pure functions | buildConversationId, SANITISE, resolveContentType | CO1 |
| Lambda expressions | All ActionListeners, stream ops, shutdown hook | CO2 |
| Predicate<T> | VALID_MESSAGE, IS_MEMBER, WITHIN_SIZE_LIMIT, ALLOWED_TYPE | CO2 |
| Function<T,R> | MARK_DELIVERED, MARK_READ, NORMALISE_EMAIL | CO2 |
| UnaryOperator<T> | SANITISE (Message → Message) | CO2 |
| BiPredicate<T,U> | CAN_MANAGE(uid, room), CAN_WRITE(uid, room) | CO2 |
| Consumer<T> | broadcastToRoom(roomId, uid, Consumer<Channel>) | CO2 |
| Supplier<T> | orElseGet(defaultMsg supplier) in tests | CO2 |
| Predicate composition | HAS_CONTENT.and(NOT_TOO_LONG).and(IS_TEXT) | CO3 |
| Predicate.negate() | IS_OFFLINE = IS_ONLINE.negate() | CO3 |
| Function.andThen() | SANITISE.andThen(MARK_DELIVERED) | CO3 |
| Optional<T> | getFileUrl(), getConversationId(), getSession() | CO4 |
| Dispatch table | Map<Action, BiConsumer<Channel, ChatFrame>> | CO4 |
| Stream API | fetchAllRooms, searchMessages, getRoomsForUser | CO5 |
| CompletableFuture | login, sendMessage, upload, joinRoom, all DB ops | CO5 |
| Method references | Objects::nonNull, Channel::isActive, Room::of | CO2 |

---

## Setup instructions

### 1. Firebase (free Spark plan)

1. Go to console.firebase.google.com → Add project → name it `funcchat-pro`
2. Build → Realtime Database → Create database → Start in test mode
3. Build → Authentication → Get started → Enable Email/Password
4. Project Settings → General → copy Web API Key and Project ID

### 2. AWS S3 (file sharing)

1. AWS Console → S3 → Create bucket → name: `funcchat-files`
2. Block all public access: ON (files delivered via pre-signed URLs only)
3. AWS Console → IAM → Create user → attach `AmazonS3FullAccess`
4. Create access keys for that user

### 3. Configure .env

```bash
cp .env.template .env
# Edit .env with your Firebase and AWS values
```

### 4. Build

```bash
mvn clean package -DskipTests
```

### 5. Run server

```bash
java -jar target/funcchat-pro-server.jar
```

Server starts on:
- WebSocket: `ws://localhost:8080/ws`
- Webhook:   `http://localhost:8081/webhook/{roomId}`

### 6. Run clients (two terminals)

```bash
# Terminal 1
mvn exec:java -Dexec.mainClass="com.chatapp.ui.ChatClientUI"

# Terminal 2
mvn exec:java -Dexec.mainClass="com.chatapp.ui.ChatClientUI"
```

### 7. Run tests (fully offline, no Firebase or AWS needed)

```bash
mvn test
```

Expected: 40+ tests, all passing, zero failures.

---

## Webhook bot integration

Send a POST request to inject a bot message into any room:

```bash
curl -X POST http://localhost:8081/webhook/YOUR_ROOM_ID \
  -H "Content-Type: application/json" \
  -d '{"text": "Build #42 passed in 2m 15s", "source": "GitHub Actions"}'
```

The message appears in the room as a bot notification (blue highlight, mono font).

---

## Firebase database schema (Phase 2)

```
funcchat-db/
├── users/{uid}/                    ← User profiles (unchanged from Phase 1)
│   ├── uid, email, displayName
│   ├── online, lastSeen
│
├── conversations/{convoId}/        ← P2P messages (unchanged)
│   └── messages/{msgId}/
│
├── rooms/{roomId}/                 ← NEW — Group rooms
│   ├── id, name, description
│   ├── createdBy, createdAt
│   ├── members: { uid: "ADMIN" | "MEMBER" }
│   └── messages/{msgId}/           ← Group messages
│       ├── id, senderId, roomId
│       ├── content, timestamp, type
│       ├── fileUrl, fileName, fileSize
│       └── readBy: { uid: true }
│
└── typing/{roomId}/{uid}/          ← NEW — Typing state (ephemeral)
    ├── isTyping: true
    └── since: timestamp
```

---

## AWS deployment (one-command after initial setup)

```bash
git push origin main   # GitHub Actions handles the rest
```

GitHub Actions will:
1. Run all 40+ unit tests
2. Build the fat JAR
3. Build and push Docker image to ECR
4. SSH into EC2 and restart the container

---

*FuncChat Pro — Phase 2 | 23CSH-312 Functional Programming in Java*
*Chandigarh University — University Institute of Engineering*
