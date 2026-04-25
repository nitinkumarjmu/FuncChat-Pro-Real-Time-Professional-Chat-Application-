# FuncChat Pro 
### Functional Programming in Java 
---
## What's in FuncChat Pro?

| Feature |
---
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

## Firebase database schema 

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
