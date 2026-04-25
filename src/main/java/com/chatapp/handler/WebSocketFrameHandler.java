package com.chatapp.handler;

import com.chatapp.model.*;
import com.chatapp.service.*;
import com.chatapp.util.ChatFrameCodec;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Netty WebSocket channel handler — Phase 2 extended.
 *
 * Functional principles:
 *   - Dispatch table Map<Action, BiConsumer<Channel, ChatFrame>>
 *     replaces all if/else and switch chains
 *   - Optional chaining for null-safe session and payload access
 *   - CompletableFuture pipelines for all async operations
 *   - Lambda handlers registered at construction (immutable after)
 */
@ChannelHandler.Sharable
public final class WebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(WebSocketFrameHandler.class);

    private final AuthService      authService;
    private final MessageService   messageService;
    private final PresenceService  presenceService;
    private final GroupRoomService groupRoomService;
    private final TypingService    typingService;
    private final FileShareService fileShareService;
    private final RoleAccessService roleAccessService;

    /** Dispatch table — O(1) lookup, no conditionals */
    private final Map<ChatFrame.Action, BiConsumer<Channel, ChatFrame>> dispatch;

    public WebSocketFrameHandler(AuthService auth,
                                  MessageService messages,
                                  PresenceService presence,
                                  GroupRoomService groupRoom,
                                  TypingService typing,
                                  FileShareService fileShare,
                                  RoleAccessService roleAccess) {
        this.authService       = auth;
        this.messageService    = messages;
        this.presenceService   = presence;
        this.groupRoomService  = groupRoom;
        this.typingService     = typing;
        this.fileShareService  = fileShare;
        this.roleAccessService = roleAccess;

        // Build dispatch table once at construction — immutable after
        this.dispatch = Map.ofEntries(
                // Phase 1 actions
                Map.entry(ChatFrame.Action.LOGIN,            this::handleLogin),
                Map.entry(ChatFrame.Action.REGISTER,         this::handleRegister),
                Map.entry(ChatFrame.Action.SEND_MESSAGE,     this::handleSendMessage),
                Map.entry(ChatFrame.Action.PING,             this::handlePing),

                // Phase 2 — Group rooms
                Map.entry(ChatFrame.Action.CREATE_ROOM,      this::handleCreateRoom),
                Map.entry(ChatFrame.Action.JOIN_ROOM,        this::handleJoinRoom),
                Map.entry(ChatFrame.Action.LEAVE_ROOM,       this::handleLeaveRoom),
                Map.entry(ChatFrame.Action.SEND_GROUP_MSG,   this::handleGroupMessage),

                // Phase 2 — Typing
                Map.entry(ChatFrame.Action.TYPING_START,     this::handleTypingStart),
                Map.entry(ChatFrame.Action.TYPING_STOP,      this::handleTypingStop),

                // Phase 2 — Files
                Map.entry(ChatFrame.Action.UPLOAD_FILE,      this::handleUploadFile),

                // Phase 2 — Search
                Map.entry(ChatFrame.Action.SEARCH_MESSAGES,  this::handleSearch),

                // Phase 2 — Admin
                Map.entry(ChatFrame.Action.REMOVE_USER,      this::handleRemoveUser),
                Map.entry(ChatFrame.Action.PROMOTE_USER,     this::handlePromoteUser)
        );
    }

    // ── Netty lifecycle ───────────────────────────────────────────────────────

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("Client connected: {}", ctx.channel().id().asShortText());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        String cid = ctx.channel().id().asShortText();
        authService.removeSession(cid)
                .ifPresent(user -> {
                    typingService.clearAllTyping(user.getUid(), user.getDisplayName());
                    presenceService.setOffline(user.getUid())
                            .thenRun(() -> presenceService.broadcastToAll(
                                    user.getUid(),
                                    ch -> send(ch, ChatFrame.presenceUpdate(user, false))));
                    log.info("Disconnected: {}", user.getDisplayName());
                });
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame text) {
            processTextFrame(ctx.channel(), text.text());
        } else if (frame instanceof CloseWebSocketFrame) {
            ctx.channel().close();
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.channel().writeAndFlush(new PongWebSocketFrame());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Channel error: {}", cause.getMessage());
        sendError(ctx.channel(), "Server error: " + cause.getMessage());
        ctx.channel().close();
    }

    // ── Frame routing ─────────────────────────────────────────────────────────

    private void processTextFrame(Channel ch, String json) {
        ChatFrameCodec.DECODER.apply(json)
                .ifPresentOrElse(
                        frame -> routeFrame(ch, frame),
                        () -> sendError(ch, "Malformed JSON frame"));
    }

    private void routeFrame(Channel ch, ChatFrame frame) {
        log.info("Action: {} from {}", frame.getAction(), ch.id().asShortText());
        Optional.ofNullable(dispatch.get(frame.getAction()))
                .ifPresentOrElse(
                        handler -> handler.accept(ch, frame),
                        () -> sendError(ch, "Unknown action: " + frame.getAction()));
    }

    // ── Register handler ──────────────────────────────────────────────────────

    private void handleRegister(Channel ch, ChatFrame frame) {
        ChatFrameCodec.payloadAsMap(frame)
                .ifPresentOrElse(creds -> {
                    String email       = String.valueOf(creds.getOrDefault("email", ""));
                    String password    = String.valueOf(creds.getOrDefault("password", ""));
                    String displayName = String.valueOf(creds.getOrDefault("displayName", ""));

                    authService.register(email, password, displayName)
                            .thenCompose(user -> {
                                authService.storeSession(ch.id().asShortText(), user);
                                return presenceService.setOnline(user, ch).thenApply(v -> user);
                            })
                            .thenCompose(user ->
                                    groupRoomService.loadRoomsFromDatabase().thenApply(v -> user))
                            .thenAccept(user -> {
                                send(ch, ChatFrame.loginSuccess(user));
                                List<com.chatapp.model.Room> rooms =
                                        groupRoomService.getRoomsForUser(user.getUid());
                                send(ch, ChatFrame.of(ChatFrame.Action.ROOM_LIST, rooms));
                                send(ch, ChatFrame.of(ChatFrame.Action.USER_LIST, presenceService.getOnlineUsers()));
                                presenceService.broadcastToAll(user.getUid(),
                                        c -> send(c, ChatFrame.presenceUpdate(user, true)));
                                log.info("Registered: {}", user.getDisplayName());
                            })
                            .exceptionally(ex -> {
                                sendError(ch, "Registration failed: " + ex.getMessage()); return null; });
                }, () -> sendError(ch, "REGISTER requires {email, password, displayName}"));
    }

    // ── Auth handler ──────────────────────────────────────────────────────────

    private void handleLogin(Channel ch, ChatFrame frame) {
        ChatFrameCodec.payloadAsMap(frame)
                .ifPresentOrElse(creds -> {
                    String email    = String.valueOf(creds.getOrDefault("email", ""));
                    String password = String.valueOf(creds.getOrDefault("password", ""));

                    authService.login(email, password)
                            .thenCompose(user -> {
                                authService.storeSession(ch.id().asShortText(), user);
                                return presenceService.setOnline(user, ch).thenApply(v -> user);
                            })
                            .thenCompose(user ->
                                    groupRoomService.loadRoomsFromDatabase().thenApply(v -> user))
                            .thenAccept(user -> {
                                send(ch, ChatFrame.loginSuccess(user));
                                // Send room list
                                List<com.chatapp.model.Room> rooms =
                                        groupRoomService.getRoomsForUser(user.getUid());
                                send(ch, ChatFrame.of(ChatFrame.Action.ROOM_LIST, rooms));
                                // Send user list
                                send(ch, ChatFrame.of(ChatFrame.Action.USER_LIST, presenceService.getOnlineUsers()));
                                // Broadcast presence
                                presenceService.broadcastToAll(user.getUid(),
                                        c -> send(c, ChatFrame.presenceUpdate(user, true)));
                                log.info("Login: {}", user.getDisplayName());
                            })
                            .exceptionally(ex -> {
                                sendError(ch, "Login failed: " + ex.getMessage()); return null; });
                }, () -> sendError(ch, "LOGIN requires {email, password}"));
    }

    // ── P2P message handler ───────────────────────────────────────────────────

    private void handleSendMessage(Channel ch, ChatFrame frame) {
        if (!isAuthenticated(ch)) return;
        ChatFrameCodec.payloadAsMessage(frame)
                .ifPresentOrElse(msg ->
                        messageService.sendMessage(msg)
                                .thenAccept(saved -> {
                                    send(ch, ChatFrame.of(ChatFrame.Action.MESSAGE_STATUS, saved));
                                    presenceService.sendToUser(saved.getReceiverId(),
                                            rc -> send(rc, ChatFrame.receiveMessage(saved)));
                                })
                                .exceptionally(ex -> {
                                    sendError(ch, "Send failed: " + ex.getMessage()); return null; }),
                        () -> sendError(ch, "Invalid message payload"));
    }

    // ── Group room handlers ───────────────────────────────────────────────────

    private void handleCreateRoom(Channel ch, ChatFrame frame) {
        if (!isAuthenticated(ch)) return;
        getUser(ch).ifPresent(user ->
                ChatFrameCodec.payloadAsMap(frame)
                        .ifPresentOrElse(payload -> {
                            String name = String.valueOf(payload.getOrDefault("name", ""));
                            String desc = String.valueOf(payload.getOrDefault("description", ""));
                            groupRoomService.createRoom(name, desc, user.getUid())
                                    .thenAccept(room -> {
                                        send(ch, ChatFrame.roomCreated(room));
                                        // Notify all online users of new room
                                        presenceService.broadcastToAll(user.getUid(),
                                                c -> send(c, ChatFrame.roomCreated(room)));
                                    })
                                    .exceptionally(ex -> {
                                        sendError(ch, ex.getMessage()); return null; });
                        }, () -> sendError(ch, "CREATE_ROOM requires {name, description}")));
    }

    private void handleJoinRoom(Channel ch, ChatFrame frame) {
        if (!isAuthenticated(ch)) return;
        getUser(ch).ifPresent(user ->
                ChatFrameCodec.payloadAsMap(frame)
                        .flatMap(p -> Optional.ofNullable((String) p.get("roomId")))
                        .ifPresentOrElse(roomId ->
                                groupRoomService.joinRoom(roomId, user.getUid())
                                        .thenAccept(room -> {
                                            send(ch, ChatFrame.of(ChatFrame.Action.ROOM_UPDATED, room));
                                            // Notify existing room members
                                            groupRoomService.broadcastToRoom(roomId, user.getUid(),
                                                    c -> send(c, ChatFrame.of(ChatFrame.Action.ROOM_UPDATED, room)));
                                        })
                                        .exceptionally(ex -> {
                                            sendError(ch, ex.getMessage()); return null; }),
                                () -> sendError(ch, "JOIN_ROOM requires {roomId}")));
    }

    private void handleLeaveRoom(Channel ch, ChatFrame frame) {
        if (!isAuthenticated(ch)) return;
        getUser(ch).ifPresent(user ->
                ChatFrameCodec.payloadAsMap(frame)
                        .flatMap(p -> Optional.ofNullable((String) p.get("roomId")))
                        .ifPresentOrElse(roomId ->
                                groupRoomService.leaveRoom(roomId, user.getUid())
                                        .thenAccept(room -> {
                                            send(ch, ChatFrame.of(ChatFrame.Action.ROOM_UPDATED, room));
                                            groupRoomService.broadcastToRoom(roomId, user.getUid(),
                                                    c -> send(c, ChatFrame.of(ChatFrame.Action.ROOM_UPDATED, room)));
                                        })
                                        .exceptionally(ex -> {
                                            sendError(ch, ex.getMessage()); return null; }),
                                () -> sendError(ch, "LEAVE_ROOM requires {roomId}")));
    }

    private void handleGroupMessage(Channel ch, ChatFrame frame) {
        if (!isAuthenticated(ch)) return;
        getUser(ch).ifPresent(user ->
                ChatFrameCodec.payloadAsMessage(frame)
                        .ifPresentOrElse(msg -> {
                            if (!roleAccessService.isMemberSync(user.getUid(), msg.getRoomId())) {
                                sendError(ch, "You are not a member of room: " + msg.getRoomId());
                                return;
                            }
                            messageService.sendGroupMessage(msg)
                                    .thenAccept(saved -> {
                                        // Broadcast to all room members
                                        groupRoomService.broadcastToRoom(
                                                saved.getRoomId(), user.getUid(),
                                                c -> send(c, ChatFrame.groupMessage(saved)));
                                        // Echo back to sender
                                        send(ch, ChatFrame.of(ChatFrame.Action.MESSAGE_STATUS, saved));
                                        // Stop typing on message send
                                        typingService.stopTyping(user.getUid(),
                                                saved.getRoomId(), user.getDisplayName());
                                    })
                                    .exceptionally(ex -> {
                                        sendError(ch, ex.getMessage()); return null; });
                        }, () -> sendError(ch, "Invalid group message payload")));
    }

    // ── Typing handlers ───────────────────────────────────────────────────────

    private void handleTypingStart(Channel ch, ChatFrame frame) {
        if (!isAuthenticated(ch)) return;
        getUser(ch).ifPresent(user ->
                ChatFrameCodec.payloadAsMap(frame)
                        .flatMap(p -> Optional.ofNullable((String) p.get("roomId")))
                        .filter(roomId -> roleAccessService.isMemberSync(user.getUid(), roomId))
                        .ifPresent(roomId ->
                                typingService.startTyping(user.getUid(), roomId, user.getDisplayName())));
    }

    private void handleTypingStop(Channel ch, ChatFrame frame) {
        if (!isAuthenticated(ch)) return;
        getUser(ch).ifPresent(user ->
                ChatFrameCodec.payloadAsMap(frame)
                        .flatMap(p -> Optional.ofNullable((String) p.get("roomId")))
                        .ifPresent(roomId ->
                                typingService.stopTyping(user.getUid(), roomId, user.getDisplayName())));
    }

    // ── File upload handler ───────────────────────────────────────────────────

    private void handleUploadFile(Channel ch, ChatFrame frame) {
        if (!isAuthenticated(ch)) return;
        getUser(ch).ifPresent(user ->
                ChatFrameCodec.payloadAsMap(frame)
                        .ifPresentOrElse(payload -> {
                            String fileName = (String) payload.get("fileName");
                            String base64   = (String) payload.get("base64Data");
                            String roomId   = (String) payload.get("roomId");
                            String peerId   = (String) payload.get("peerId");
                            long fileSize   = payload.containsKey("fileSize")
                                    ? ((Number) payload.get("fileSize")).longValue() : 0L;

                            if (fileName == null || base64 == null || (roomId == null && peerId == null)) {
                                sendError(ch, "UPLOAD_FILE requires {fileName, base64Data, roomId or peerId}");
                                return;
                            }

                            if (roomId != null && !roleAccessService.isMemberSync(user.getUid(), roomId)) {
                                sendError(ch, "Not a member of room: " + roomId);
                                return;
                            }

                            Optional<String> validationError = fileShareService.validate(fileName, fileSize);
                            if (validationError.isPresent()) {
                                sendError(ch, validationError.get());
                                return;
                            }
                            fileShareService.uploadBase64(fileName, base64)
                                    .thenCompose(url -> {
                                        if (peerId != null) {
                                            Message p2pMsg = Message.system(user.getUid(), peerId, "Shared a file: " + fileName, Message.MessageType.FILE)
                                                    .withFileUrl(url).withFileName(fileName).withFileSize(fileSize);
                                            return messageService.sendMessage(p2pMsg);
                                        }
                                        Message roomMsg = Message.fileMessage(user.getUid(), roomId, url, fileName, fileSize);
                                        return messageService.sendGroupMessage(roomMsg);
                                    })
                                    .thenAccept(saved -> {
                                        if (saved.getRoomId() != null) {
                                            groupRoomService.broadcastToRoom(saved.getRoomId(), user.getUid(),
                                                    c -> send(c, ChatFrame.fileShared(saved)));
                                            send(ch, ChatFrame.fileShared(saved));
                                        } else if (saved.getReceiverId() != null) {
                                            presenceService.sendToUser(saved.getReceiverId(),
                                                    rc -> send(rc, ChatFrame.fileShared(saved)));
                                            send(ch, ChatFrame.fileShared(saved));
                                        }
                                    })
                                    .exceptionally(ex -> {
                                        sendError(ch, "Upload failed: " + ex.getMessage());
                                        return null;
                                    });
                        }, () -> sendError(ch, "Invalid file upload payload")));
    }

    // ── Search handler ────────────────────────────────────────────────────────

    private void handleSearch(Channel ch, ChatFrame frame) {
        if (!isAuthenticated(ch)) return;
        getUser(ch).ifPresent(user ->
                ChatFrameCodec.payloadAsMap(frame)
                        .ifPresentOrElse(payload -> {
                            String query  = (String) payload.get("query");
                            String roomId = (String) payload.get("roomId");
                            String peerId = (String) payload.get("peerId");
                            String finalQuery = (query == null) ? "" : query;

                            CompletableFuture<List<com.chatapp.model.Message>> future;
                            if (roomId != null) {
                                future = finalQuery.isBlank() 
                                        ? messageService.getRoomHistory(roomId)
                                        : messageService.searchRoom(roomId, finalQuery);
                            } else if (peerId != null) {
                                future = finalQuery.isBlank()
                                        ? messageService.getHistory(user.getUid(), peerId)
                                        : messageService.searchP2P(user.getUid(), peerId, finalQuery);
                            } else {
                                future = messageService.getRoomHistory("global");
                            }

                            future.thenAccept(results ->
                                            send(ch, ChatFrame.of(ChatFrame.Action.SEARCH_RESULTS, results)))
                                    .exceptionally(ex -> {
                                        sendError(ch, "Search failed: " + ex.getMessage()); return null; });
                        }, () -> sendError(ch, "SEARCH_MESSAGES requires {query, roomId/peerId?}")));
    }

    // ── Admin handlers ────────────────────────────────────────────────────────

    private void handleRemoveUser(Channel ch, ChatFrame frame) {
        if (!isAuthenticated(ch)) return;
        getUser(ch).ifPresent(admin ->
                ChatFrameCodec.payloadAsMap(frame)
                        .ifPresentOrElse(payload -> {
                            String roomId    = (String) payload.get("roomId");
                            String targetUid = (String) payload.get("targetUid");
                            if (roomId == null || targetUid == null) {
                                sendError(ch, "REMOVE_USER requires {roomId, targetUid}");
                                return;
                            }
                            groupRoomService.removeUser(roomId, admin.getUid(), targetUid)
                                    .thenAccept(updated -> {
                                        // Notify target user
                                        presenceService.sendToUser(targetUid,
                                                c -> send(c, ChatFrame.userRemoved(targetUid, roomId)));
                                        // Notify room
                                        groupRoomService.broadcastToRoom(roomId, admin.getUid(),
                                                c -> send(c, ChatFrame.of(ChatFrame.Action.ROOM_UPDATED, updated)));
                                        send(ch, ChatFrame.of(ChatFrame.Action.ROOM_UPDATED, updated));
                                    })
                                    .exceptionally(ex -> {
                                        sendError(ch, ex.getMessage()); return null; });
                        }, () -> sendError(ch, "Invalid payload")));
    }

    private void handlePromoteUser(Channel ch, ChatFrame frame) {
        if (!isAuthenticated(ch)) return;
        getUser(ch).ifPresent(admin ->
                ChatFrameCodec.payloadAsMap(frame)
                        .ifPresentOrElse(payload -> {
                            String roomId    = (String) payload.get("roomId");
                            String targetUid = (String) payload.get("targetUid");
                            if (roomId == null || targetUid == null) {
                                sendError(ch, "PROMOTE_USER requires {roomId, targetUid}");
                                return;
                            }
                            if (!roleAccessService.isAdminSync(admin.getUid(), roomId)) {
                                sendError(ch, "Only admins can promote users");
                                return;
                            }
                            groupRoomService.getRoom(roomId)
                                    .thenCompose(opt -> {
                                        if (opt.isEmpty()) return CompletableFuture.completedFuture(null);
                                        Room promoted = opt.get().withAdmin(targetUid);
                                        // Persist to Firebase and update cache before broadcasting
                                        return groupRoomService.persistRoom(promoted)
                                                .thenAccept(saved -> {
                                                    groupRoomService.broadcastToRoom(roomId, admin.getUid(),
                                                            c -> send(c, ChatFrame.of(ChatFrame.Action.ROOM_UPDATED, saved)));
                                                    send(ch, ChatFrame.of(ChatFrame.Action.ROOM_UPDATED, saved));
                                                })
                                                .thenApply(v -> null);
                                    })
                                    .exceptionally(ex -> { sendError(ch, ex.getMessage()); return null; });
                        }, () -> sendError(ch, "Invalid payload")));
    }

    private void handlePing(Channel ch, ChatFrame frame) {
        send(ch, ChatFrame.pong());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isAuthenticated(Channel ch) {
        if (!authService.isAuthenticated(ch.id().asShortText())) {
            sendError(ch, "Not authenticated. Please login first.");
            return false;
        }
        return true;
    }

    private Optional<com.chatapp.model.User> getUser(Channel ch) {
        return authService.getSession(ch.id().asShortText());
    }

    private void send(Channel ch, ChatFrame frame) {
        ChatFrameCodec.ENCODER.apply(frame)
                .filter(json -> ch.isActive())
                .ifPresent(json -> ch.writeAndFlush(new TextWebSocketFrame(json)));
    }

    private void sendError(Channel ch, String message) {
        send(ch, ChatFrame.error(message));
    }
}
