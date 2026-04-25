package com.chatapp.ui;

import com.chatapp.model.*;
import com.chatapp.util.ChatFrameCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * FuncChat Pro — Enhanced Java Swing client (Phase 2).
 *
 * New UI components vs Phase 1:
 *   - Rooms sidebar panel with + button
 *   - Typing indicator label
 *   - File attachment button + upload progress
 *   - Search bar in chat header
 *   - Right panel: members list with admin controls
 *   - Bot message display (distinct visual style)
 *
 * Functional principles maintained:
 *   - All ActionListeners as lambdas
 *   - Function<Message, JPanel> as pure message bubble factory
 *   - Consumer<String> for incoming JSON processing
 *   - Optional chaining for null-safe state access
 *   - CompletableFuture for non-blocking WebSocket operations
 */
public final class ChatClientUI extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(ChatClientUI.class);

    // ── Immutable UI constants ────────────────────────────────────────────────
    private static final Color  C_BG        = new Color(245, 247, 250);
    private static final Color  C_SIDEBAR   = new Color(24, 28, 42);
    private static final Color  C_SENT      = new Color(88, 80, 236);
    private static final Color  C_RECEIVED  = Color.WHITE;
    private static final Color  C_BOT       = new Color(240, 249, 255);
    private static final Color  C_ONLINE    = new Color(34, 197, 94);
    private static final Color  C_OFFLINE   = new Color(100, 116, 139);
    private static final Color  C_ACCENT    = new Color(99, 102, 241);
    private static final Color  C_HOVER     = new Color(44, 50, 70);
    private static final Color  C_DIVIDER   = new Color(50, 56, 78);
    private static final Font   F_BODY      = new Font("Segoe UI", Font.PLAIN, 14);
    private static final Font   F_BOLD      = new Font("Segoe UI", Font.BOLD, 14);
    private static final Font   F_SMALL     = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font   F_MONO      = new Font("Consolas", Font.PLAIN, 12);
    private static final String WS_URL      = "ws://localhost:8080/ws";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // ── UI Components ─────────────────────────────────────────────────────────
    private JPanel            messagesPanel;
    private JScrollPane       messagesScroll;
    private JTextField        inputField;
    private JButton           sendButton;
    private JButton           attachButton;
    private JLabel            statusLabel;
    private JLabel            headerNameLabel;
    private JLabel            headerStatusLabel;
    private JLabel            typingLabel;
    private JTextField        searchField;
    private JPanel            membersPanel;

    // Sidebar lists
    private DefaultListModel<User>   dmListModel   = new DefaultListModel<>();
    private DefaultListModel<String> roomListModel = new DefaultListModel<>();
    private JList<User>              dmList;
    private JList<String>            roomList;

    // ── State ─────────────────────────────────────────────────────────────────
    private WebSocket    webSocket;
    private User         currentUser;
    private String       activePeerId;    // for P2P
    private String       activeRoomId;    // for group
    private boolean      inGroupMode  = false;
    private boolean      connected    = false;
    private final Map<String, Room> knownRooms = new ConcurrentHashMap<>();

    // ── Pure function: Message → JPanel ──────────────────────────────────────
    private final Function<Message, JPanel> bubbleFactory = this::createBubble;

    // ── Consumer: handles incoming JSON ──────────────────────────────────────
    private final Consumer<String> onIncoming = this::processFrame;

    // ── Typing debounce ───────────────────────────────────────────────────────
    private ScheduledExecutorService typingDebounce =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "typing-debounce");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> typingStopTask;

    public ChatClientUI() {
        super("FuncChat Pro");
        buildUI();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 720);
        setMinimumSize(new Dimension(800, 560));
        setLocationRelativeTo(null);
        Runtime.getRuntime().addShutdownHook(new Thread(this::disconnect, "ui-shutdown"));
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        setLayout(new BorderLayout());
        getContentPane().setBackground(C_BG);
        add(buildSidebar(),    BorderLayout.WEST);
        add(buildChatArea(),   BorderLayout.CENTER);
        add(buildMembersPanel(), BorderLayout.EAST);
        SwingUtilities.invokeLater(this::showLoginDialog);
    }

    // ── Sidebar ───────────────────────────────────────────────────────────────

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setPreferredSize(new Dimension(240, 0));
        sidebar.setBackground(C_SIDEBAR);

        // App title
        JLabel title = new JLabel("  FuncChat Pro");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("Segoe UI", Font.BOLD, 17));
        title.setBorder(new EmptyBorder(18, 12, 12, 12));
        sidebar.add(title, BorderLayout.NORTH);

        // Scrollable list panel
        JPanel lists = new JPanel();
        lists.setLayout(new BoxLayout(lists, BoxLayout.Y_AXIS));
        lists.setBackground(C_SIDEBAR);

        // Direct Messages section
        lists.add(sectionLabel("DIRECT MESSAGES"));
        dmList = buildUserList(dmListModel);
        dmList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                User sel = dmList.getSelectedValue();
                if (sel != null && (currentUser == null || !sel.getUid().equals(currentUser.getUid()))) openDirectChat(sel);
            }
        });
        JScrollPane dmScroll = noScrollBar(dmList);
        dmScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
        lists.add(dmScroll);

        // Rooms section header with + button
        JPanel roomHeader = new JPanel(new BorderLayout());
        roomHeader.setBackground(C_SIDEBAR);
        roomHeader.setBorder(new EmptyBorder(12, 12, 4, 8));
        JLabel roomLabel = new JLabel("ROOMS");
        roomLabel.setForeground(C_OFFLINE);
        roomLabel.setFont(F_SMALL);
        JButton addRoom = new JButton("+ New");
        addRoom.setFont(new Font("Segoe UI", Font.BOLD, 12));
        addRoom.setForeground(C_OFFLINE);
        addRoom.setBackground(C_SIDEBAR);
        addRoom.setBorderPainted(false);
        addRoom.setFocusPainted(false);
        addRoom.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addRoom.addActionListener(e -> showCreateRoomDialog()); // lambda
        roomHeader.add(roomLabel, BorderLayout.WEST);
        roomHeader.add(addRoom,   BorderLayout.EAST);
        lists.add(roomHeader);

        roomList = buildList(roomListModel);
        roomList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                String sel = roomList.getSelectedValue();
                if (sel != null) openRoom(sel);
            }
        });
        lists.add(noScrollBar(roomList));

        JScrollPane listsScroll = new JScrollPane(lists);
        listsScroll.setBorder(null);
        listsScroll.setBackground(C_SIDEBAR);
        sidebar.add(listsScroll, BorderLayout.CENTER);

        // Status bar at bottom
        statusLabel = new JLabel("  Disconnected");
        statusLabel.setForeground(C_OFFLINE);
        statusLabel.setFont(F_SMALL);
        statusLabel.setBorder(new CompoundBorder(
                new MatteBorder(1, 0, 0, 0, C_DIVIDER),
                new EmptyBorder(8, 12, 10, 12)));
        sidebar.add(statusLabel, BorderLayout.SOUTH);
        return sidebar;
    }

    // ── Chat area ─────────────────────────────────────────────────────────────

    private JPanel buildChatArea() {
        JPanel chat = new JPanel(new BorderLayout());
        chat.setBackground(C_BG);

        // Header
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Color.WHITE);
        header.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, new Color(226, 232, 240)),
                new EmptyBorder(12, 20, 12, 16)));

        JPanel headerInfo = new JPanel();
        headerInfo.setLayout(new BoxLayout(headerInfo, BoxLayout.Y_AXIS));
        headerInfo.setOpaque(false);

        headerNameLabel = new JLabel("← Select a chat");
        headerNameLabel.setFont(F_BOLD);
        
        headerStatusLabel = new JLabel("Choose a contact to start messaging");
        headerStatusLabel.setFont(F_SMALL);
        headerStatusLabel.setForeground(C_OFFLINE);

        headerInfo.add(headerNameLabel);
        headerInfo.add(headerStatusLabel);

        // Search bar in header
        searchField = new JTextField();
        searchField.setFont(F_SMALL);
        searchField.setPreferredSize(new Dimension(200, 28));
        searchField.setBorder(new CompoundBorder(
                new LineBorder(new Color(203, 213, 225), 1, true),
                new EmptyBorder(4, 8, 4, 8)));
        searchField.putClientProperty("JTextField.placeholderText", "Search messages...");
        searchField.addActionListener(e -> performSearch()); // lambda
        header.add(headerInfo, BorderLayout.WEST);
        header.add(searchField,     BorderLayout.EAST);
        chat.add(header, BorderLayout.NORTH);

        // Messages panel
        messagesPanel = new JPanel();
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(C_BG);
        messagesPanel.setBorder(new EmptyBorder(12, 16, 8, 16));
        messagesScroll = new JScrollPane(messagesPanel);
        messagesScroll.setBorder(null);
        messagesScroll.setBackground(C_BG);
        messagesScroll.getVerticalScrollBar().setUnitIncrement(16);
        chat.add(messagesScroll, BorderLayout.CENTER);

        // Bottom: typing indicator + input bar
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(Color.WHITE);

        typingLabel = new JLabel("");
        typingLabel.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        typingLabel.setForeground(C_OFFLINE);
        typingLabel.setBorder(new EmptyBorder(4, 20, 0, 0));
        bottomPanel.add(typingLabel, BorderLayout.NORTH);

        JPanel inputBar = new JPanel(new BorderLayout(8, 0));
        inputBar.setBackground(Color.WHITE);
        inputBar.setBorder(new CompoundBorder(
                new MatteBorder(1, 0, 0, 0, new Color(226, 232, 240)),
                new EmptyBorder(10, 16, 10, 16)));

        attachButton = new JButton("\uD83D\uDCCE Attach");
        attachButton.setFont(new Font("Segoe UI", Font.BOLD, 13));
        attachButton.setForeground(Color.WHITE);
        attachButton.setBackground(new Color(71, 85, 105));
        attachButton.setBorderPainted(false);
        attachButton.setFocusPainted(false);
        attachButton.setOpaque(true);
        attachButton.setContentAreaFilled(true);
        attachButton.setEnabled(false);
        attachButton.setToolTipText("Attach file (images, PDF, ZIP — max 5 MB)");
        attachButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        attachButton.setPreferredSize(new Dimension(100, 36));
        attachButton.addActionListener(e -> openFileChooser()); // lambda
        // Hover effect — lambda
        attachButton.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                if (attachButton.isEnabled()) attachButton.setBackground(C_ACCENT);
            }
            @Override public void mouseExited(MouseEvent e) {
                attachButton.setBackground(new Color(71, 85, 105));
            }
        });

        inputField = new JTextField();
        inputField.setFont(F_BODY);
        inputField.setBorder(new CompoundBorder(
                new LineBorder(new Color(203, 213, 225), 1, true),
                new EmptyBorder(8, 12, 8, 12)));
        inputField.setBackground(new Color(248, 250, 252));
        inputField.putClientProperty("JTextField.placeholderText", "Type your message here and press Enter to send...");
        inputField.setEnabled(false);
        inputField.addActionListener(e -> sendMessage()); // lambda
        // Typing indicator: send TYPING_START on key press
        inputField.addKeyListener(new KeyAdapter() {
            @Override public void keyTyped(KeyEvent e) { sendTypingStart(); }
        });

        sendButton = new JButton("Send");
        sendButton.setFont(F_BOLD);
        sendButton.setBackground(C_ACCENT);
        sendButton.setForeground(Color.WHITE);
        sendButton.setFocusPainted(false);
        sendButton.setBorderPainted(false);
        sendButton.setOpaque(true);
        sendButton.setEnabled(false);
        sendButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sendButton.addActionListener(e -> sendMessage()); // lambda

        inputBar.add(attachButton, BorderLayout.WEST);
        inputBar.add(inputField,   BorderLayout.CENTER);
        inputBar.add(sendButton,   BorderLayout.EAST);
        bottomPanel.add(inputBar, BorderLayout.CENTER);
        chat.add(bottomPanel, BorderLayout.SOUTH);
        return chat;
    }

    // ── Members panel (right side) ────────────────────────────────────────────

    private JPanel buildMembersPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(200, 0));
        panel.setBackground(new Color(248, 250, 252));
        panel.setBorder(new MatteBorder(0, 1, 0, 0, new Color(226, 232, 240)));

        JLabel title = new JLabel("  Members");
        title.setFont(F_BOLD);
        title.setBorder(new EmptyBorder(14, 12, 10, 12));
        panel.add(title, BorderLayout.NORTH);

        membersPanel = new JPanel();
        membersPanel.setLayout(new BoxLayout(membersPanel, BoxLayout.Y_AXIS));
        membersPanel.setBackground(new Color(248, 250, 252));
        JScrollPane scroll = new JScrollPane(membersPanel);
        scroll.setBorder(null);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    // ── Login dialog ──────────────────────────────────────────────────────────

    private void showLoginDialog() {
        JDialog dlg = new JDialog(this, "Sign in to FuncChat Pro", true);
        dlg.setResizable(false);
        dlg.setLocationRelativeTo(this);

        // ── Outer panel with padding ──────────────────────────────────────────
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(Color.WHITE);
        root.setBorder(new EmptyBorder(24, 36, 28, 36));

        // Title
        JLabel titleLabel = new JLabel("FuncChat Pro");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 26));
        titleLabel.setForeground(C_ACCENT);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(titleLabel);
        root.add(Box.createVerticalStrut(4));

        JLabel subtitle = new JLabel("Sign in or create an account");
        subtitle.setFont(F_SMALL);
        subtitle.setForeground(C_OFFLINE);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(subtitle);
        root.add(Box.createVerticalStrut(20));

        // Helper to build a labelled field row
        JTextField     emailF = new JTextField("demo@test.com");
        JPasswordField passF  = new JPasswordField("password123");
        JTextField     nameF  = new JTextField("Demo User");

        for (Object[] row : new Object[][]{
                {"Email",        emailF},
                {"Password",     passF},
                {"Display name", nameF}
        }) {
            JLabel lbl = new JLabel((String) row[0]);
            lbl.setFont(new Font("Segoe UI", Font.BOLD, 12));
            lbl.setForeground(new Color(51, 65, 85));
            lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(lbl);
            root.add(Box.createVerticalStrut(4));

            JComponent field = (JComponent) row[1];
            field.setFont(F_BODY);
            field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            field.setPreferredSize(new Dimension(340, 38));
            field.setAlignmentX(Component.LEFT_ALIGNMENT);
            if (field instanceof JTextField tf) {
                tf.setBorder(new CompoundBorder(
                        new LineBorder(new Color(203, 213, 225), 1, true),
                        new EmptyBorder(6, 10, 6, 10)));
            }
            root.add(field);
            root.add(Box.createVerticalStrut(14));
        }

        root.add(Box.createVerticalStrut(6));

        // Buttons
        JButton loginBtn = new JButton("Sign In");
        loginBtn.setFont(new Font("Segoe UI", Font.BOLD, 14));
        loginBtn.setBackground(C_ACCENT);
        loginBtn.setForeground(Color.WHITE);
        loginBtn.setFocusPainted(false);
        loginBtn.setBorderPainted(false);
        loginBtn.setOpaque(true);
        loginBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JButton regBtn = new JButton("Register");
        regBtn.setFont(new Font("Segoe UI", Font.BOLD, 14));
        regBtn.setBackground(new Color(71, 85, 105));
        regBtn.setForeground(Color.WHITE);
        regBtn.setFocusPainted(false);
        regBtn.setBorderPainted(false);
        regBtn.setOpaque(true);
        regBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPanel btns = new JPanel(new GridLayout(1, 2, 12, 0));
        btns.setBackground(Color.WHITE);
        btns.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        btns.setAlignmentX(Component.LEFT_ALIGNMENT);
        btns.add(loginBtn);
        btns.add(regBtn);
        root.add(btns);

        dlg.setContentPane(root);
        dlg.pack();   // auto-size to fit all content
        dlg.setLocationRelativeTo(this);

        // Lambdas for button actions
        loginBtn.addActionListener(e -> doAuth(emailF.getText(),
                new String(passF.getPassword()), nameF.getText(), false, dlg));
        regBtn.addActionListener(e -> doAuth(emailF.getText(),
                new String(passF.getPassword()), nameF.getText(), true, dlg));
        dlg.setVisible(true);
    }

    private void doAuth(String email, String password, String name,
                         boolean register, JDialog dlg) {
        connectWebSocket()
                .thenRun(() -> {
                    if (register) {
                        Map<String, String> creds = Map.of(
                                "email", email, "password", password, "displayName", name);
                        sendFrame(ChatFrame.of(ChatFrame.Action.REGISTER, creds));
                    } else {
                        Map<String, String> creds = Map.of(
                                "email", email, "password", password);
                        sendFrame(ChatFrame.of(ChatFrame.Action.LOGIN, creds));
                    }
                    SwingUtilities.invokeLater(dlg::dispose);
                })
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(dlg,
                                    "Connection failed: " + ex.getMessage(),
                                    "Error", JOptionPane.ERROR_MESSAGE));
                    return null;
                });
    }

    // ── Create room dialog ────────────────────────────────────────────────────

    private void showCreateRoomDialog() {
        if (!connected) { JOptionPane.showMessageDialog(this, "Please log in first."); return; }
        JPanel panel = new JPanel(new GridLayout(4, 1, 6, 6));
        JTextField nameF = new JTextField();
        JTextField descF = new JTextField();
        panel.add(new JLabel("Room name:"));   panel.add(nameF);
        panel.add(new JLabel("Description:")); panel.add(descF);

        int result = JOptionPane.showConfirmDialog(this, panel,
                "Create new room", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION && !nameF.getText().isBlank()) {
            Map<String, String> payload = Map.of(
                    "name", nameF.getText().trim(),
                    "description", descF.getText().trim());
            sendFrame(ChatFrame.of(ChatFrame.Action.CREATE_ROOM, payload));
        }
    }

    // ── WebSocket connection ──────────────────────────────────────────────────

    private CompletableFuture<Void> connectWebSocket() {
        HttpClient client = HttpClient.newHttpClient();
        return client.newWebSocketBuilder()
                .buildAsync(URI.create(WS_URL), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        onIncoming.accept(data.toString());
                        return WebSocket.Listener.super.onText(ws, data, last);
                    }
                    @Override
                    public void onOpen(WebSocket ws) {
                        webSocket = ws; connected = true;
                        SwingUtilities.invokeLater(() -> setConnectionStatus(true));
                        WebSocket.Listener.super.onOpen(ws);
                    }
                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                        connected = false;
                        SwingUtilities.invokeLater(() -> setConnectionStatus(false));
                        return WebSocket.Listener.super.onClose(ws, code, reason);
                    }
                    @Override
                    public void onError(WebSocket ws, Throwable err) {
                        log.error("WS error: {}", err.getMessage());
                        WebSocket.Listener.super.onError(ws, err);
                    }
                })
                .thenAccept(ws -> { webSocket = ws; log.info("WS connected"); });
    }

    // ── Incoming frame processor ──────────────────────────────────────────────

    private void processFrame(String json) {
        ChatFrameCodec.DECODER.apply(json)
                .ifPresent(frame -> SwingUtilities.invokeLater(() -> {
                    switch (frame.getAction()) {
                        case LOGIN_SUCCESS   -> onLoginSuccess(frame);
                        case RECEIVE_MESSAGE -> onP2PMessage(frame);
                        case GROUP_MESSAGE,
                             BOT_MESSAGE     -> onGroupMessage(frame);
                        case FILE_SHARED     -> onFileShared(frame);
                        case ROOM_CREATED,
                             ROOM_UPDATED    -> onRoomUpdate(frame);
                        case ROOM_LIST       -> onRoomList(frame);
                        case TYPING_UPDATE   -> onTypingUpdate(frame);
                        case SEARCH_RESULTS  -> onSearchResults(frame);
                        case USER_ONLINE,
                             USER_OFFLINE    -> onPresenceUpdate(frame);
                        case USER_LIST       -> onUserList(frame);
                        case USER_REMOVED    -> onUserRemoved(frame);
                        case ERROR           -> onError(frame);
                        default              -> log.debug("Unhandled: {}", frame.getAction());
                    }
                }));
    }

    private void onLoginSuccess(ChatFrame frame) {
        ChatFrameCodec.payloadAsUser(frame).ifPresent(user -> {
            currentUser = user;
            setTitle("FuncChat Pro — " + user.getDisplayName());
            statusLabel.setText("  " + user.getDisplayName());
            statusLabel.setForeground(C_ONLINE);
            log.info("Logged in as: {}", user.getDisplayName());
        });
    }

    private void onP2PMessage(ChatFrame frame) {
        ChatFrameCodec.payloadAsMessage(frame).ifPresent(msg -> {
            if (!inGroupMode && activePeerId != null && activePeerId.equals(msg.getSenderId())) {
                appendMessage(msg);
            } else if (activePeerId == null && activeRoomId == null) {
                // Auto-open chat if idle
                User peer = null;
                for (int i = 0; i < dmListModel.size(); i++) {
                    if (dmListModel.get(i).getUid().equals(msg.getSenderId())) {
                        peer = dmListModel.get(i);
                        break;
                    }
                }
                if (peer != null) {
                    openDirectChat(peer);
                } else {
                    activePeerId = msg.getSenderId();
                    inGroupMode = false;
                    headerNameLabel.setText("Chat with: " + activePeerId);
                    headerStatusLabel.setText("Active now");
                    headerStatusLabel.setForeground(C_ONLINE);
                    inputField.setEnabled(true); sendButton.setEnabled(true);
                    appendMessage(msg);
                }
            } else {
                // Show toast notification instead of appending into the wrong chat
                JOptionPane.showMessageDialog(this, "New message from " + msg.getSenderId(),
                        "New Message", JOptionPane.INFORMATION_MESSAGE);
            }
        });
    }

    private void onGroupMessage(ChatFrame frame) {
        ChatFrameCodec.payloadAsMessage(frame).ifPresent(msg -> {
            if (inGroupMode && msg.getRoomId().equals(activeRoomId)) {
                appendMessage(msg);
            }
        });
    }

    private void onFileShared(ChatFrame frame) {
        ChatFrameCodec.payloadAsMessage(frame).ifPresent(msg -> {
            if (inGroupMode && msg.getRoomId() != null && msg.getRoomId().equals(activeRoomId)) {
                appendMessage(msg);
            } else if (!inGroupMode && msg.getSenderId() != null && msg.getSenderId().equals(activePeerId)) {
                appendMessage(msg);
            } else if (!inGroupMode && activePeerId == null && activeRoomId == null) {
                // Auto-open if idle
                activePeerId = msg.getSenderId();
                headerNameLabel.setText("Chat with: " + activePeerId);
                inputField.setEnabled(true); sendButton.setEnabled(true);
                appendMessage(msg);
            }
        });
    }

    private void onRoomList(ChatFrame frame) {
        ChatFrameCodec.payloadAsMap(frame).ifPresent(data -> {});
        // Try as list
        try {
            Object payload = frame.getPayload();
            if (payload instanceof List<?> rooms) {
                roomListModel.clear();
                rooms.stream()
                        .map(r -> {
                            try {
                                com.fasterxml.jackson.databind.ObjectMapper om =
                                        new com.fasterxml.jackson.databind.ObjectMapper();
                                return om.readValue(om.writeValueAsString(r), Room.class);
                            } catch (Exception e) { return null; }
                        })
                        .filter(Objects::nonNull)
                        .forEach(room -> {
                            knownRooms.put(room.getId(), room);
                            roomListModel.addElement(room.getName() + " [" + room.getId().substring(0,8) + "]");
                        });
            }
        } catch (Exception ignored) {}
    }

    private void onRoomUpdate(ChatFrame frame) {
        ChatFrameCodec.payloadAsRoom(frame).ifPresent(room -> {
            knownRooms.put(room.getId(), room);
            // Update room list
            boolean found = false;
            for (int i = 0; i < roomListModel.size(); i++) {
                if (roomListModel.get(i).contains(room.getId().substring(0, 8))) {
                    roomListModel.set(i, room.getName() + " [" + room.getId().substring(0,8) + "]");
                    found = true; break;
                }
            }
            if (!found) roomListModel.addElement(room.getName() + " [" + room.getId().substring(0,8) + "]");
            // Refresh members panel if viewing this room
            if (inGroupMode && room.getId().equals(activeRoomId)) {
                refreshMembersPanel(room);
            }
        });
    }

    private void onTypingUpdate(ChatFrame frame) {
        try {
            Object p = frame.getPayload();
            if (p == null) return;
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            ChatFrame.TypingPayload tp = om.readValue(om.writeValueAsString(p),
                    ChatFrame.TypingPayload.class);
            if (inGroupMode && tp.roomId().equals(activeRoomId)) {
                typingLabel.setText(tp.typing()
                        ? tp.displayName() + " is typing…"
                        : "");
            }
        } catch (Exception ignored) {}
    }

    private void onSearchResults(ChatFrame frame) {
        messagesPanel.removeAll();
        try {
            Object payload = frame.getPayload();
            if (payload instanceof List<?> results) {
                results.stream()
                        .map(r -> {
                            try {
                                com.fasterxml.jackson.databind.ObjectMapper om =
                                        new com.fasterxml.jackson.databind.ObjectMapper();
                                return om.readValue(om.writeValueAsString(r), Message.class);
                            } catch (Exception e) { return null; }
                        })
                        .filter(Objects::nonNull)
                        .forEach(msg -> appendMessage(msg));
            }
        } catch (Exception ignored) {}
        messagesPanel.add(new JLabel("  — End of search results —") {{
            setFont(F_SMALL); setForeground(C_OFFLINE); setAlignmentX(CENTER_ALIGNMENT);
        }});
        messagesPanel.revalidate(); messagesPanel.repaint();
    }

    private void onUserList(ChatFrame frame) {
        try {
            Object payload = frame.getPayload();
            if (payload instanceof List<?> users) {
                dmListModel.clear();
                users.stream()
                        .map(u -> {
                            try {
                                com.fasterxml.jackson.databind.ObjectMapper om =
                                        new com.fasterxml.jackson.databind.ObjectMapper();
                                return om.readValue(om.writeValueAsString(u), User.class);
                            } catch (Exception e) { return null; }
                        })
                        .filter(Objects::nonNull)
                        .forEach(user -> {
                            if (currentUser == null || !user.getUid().equals(currentUser.getUid())) {
                                dmListModel.addElement(user);
                            }
                        });
            }
        } catch (Exception ignored) {}
    }

    private void onPresenceUpdate(ChatFrame frame) {
        boolean online = frame.getAction() == ChatFrame.Action.USER_ONLINE;
        try {
            Object payload = frame.getPayload();
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            User updatedUser = om.readValue(om.writeValueAsString(payload), User.class);
            if (currentUser != null && updatedUser.getUid().equals(currentUser.getUid())) return;

            User finalUser = updatedUser.withOnlineStatus(online);
            boolean found = false;
            for (int i = 0; i < dmListModel.size(); i++) {
                if (dmListModel.get(i).getUid().equals(finalUser.getUid())) {
                    dmListModel.set(i, finalUser);
                    found = true; break;
                }
            }
            if (!found) dmListModel.addElement(finalUser);

            // Update header if this is the active peer
            if (!inGroupMode && activePeerId != null && activePeerId.equals(finalUser.getUid())) {
                headerStatusLabel.setText(online ? "Online" : "Offline");
                headerStatusLabel.setForeground(online ? C_ONLINE : C_OFFLINE);
            }
        } catch (Exception ignored) {}
    }

    private void onUserRemoved(ChatFrame frame) {
        if (currentUser == null) return;
        try {
            Object p = frame.getPayload();
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            ChatFrame.RoomUserPayload rp = om.readValue(om.writeValueAsString(p),
                    ChatFrame.RoomUserPayload.class);
            if (currentUser.getUid().equals(rp.uid())) {
                JOptionPane.showMessageDialog(this,
                        "You have been removed from room: " + rp.roomId(),
                        "Removed", JOptionPane.INFORMATION_MESSAGE);
                if (activeRoomId != null && activeRoomId.equals(rp.roomId())) {
                    messagesPanel.removeAll(); messagesPanel.revalidate();
                    headerNameLabel.setText("Select a conversation");
                    headerStatusLabel.setText("");
                    activeRoomId = null; inGroupMode = false;
                }
            }
        } catch (Exception ignored) {}
    }

    private void onError(ChatFrame frame) {
        ChatFrameCodec.payloadAsString(frame).ifPresent(err ->
                JOptionPane.showMessageDialog(this, err, "Error", JOptionPane.ERROR_MESSAGE));
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void openDirectChat(User peer) {
        inGroupMode  = false;
        activeRoomId = null;
        activePeerId = peer.getUid();
        headerNameLabel.setText(peer.getDisplayName());
        headerStatusLabel.setText(peer.isOnline() ? "Online" : "Offline");
        headerStatusLabel.setForeground(peer.isOnline() ? C_ONLINE : C_OFFLINE);
        messagesPanel.removeAll(); messagesPanel.revalidate();
        inputField.setEnabled(true); sendButton.setEnabled(true);
        inputField.requestFocusInWindow();
        attachButton.setEnabled(true);
        typingLabel.setText("");
        membersPanel.removeAll(); membersPanel.revalidate();
        // Load P2P history
        sendFrame(ChatFrame.of(ChatFrame.Action.SEARCH_MESSAGES,
                Map.of("query", "", "peerId", peer.getUid())));
    }

    private void openRoom(String entry) {
        if (currentUser == null) return;
        // Find room by name prefix match
        String namePart = entry.replaceAll(" \\[.*", "");
        knownRooms.values().stream()
                .filter(r -> r.getName().equals(namePart))
                .findFirst()
                .ifPresent(room -> {
                    // Check if already a member
                    if (!room.hasMember(currentUser.getUid())) {
                        int choice = JOptionPane.showConfirmDialog(this,
                                "You are not a member of #" + room.getName() + ". Would you like to join?",
                                "Join Room", JOptionPane.YES_NO_OPTION);
                        if (choice == JOptionPane.YES_OPTION) {
                            sendFrame(ChatFrame.of(ChatFrame.Action.JOIN_ROOM, Map.of("roomId", room.getId())));
                        }
                        return;
                    }

                    inGroupMode  = true;
                    activeRoomId = room.getId();
                    activePeerId = null;
                    headerNameLabel.setText("# " + room.getName());
                    headerStatusLabel.setText(room.memberCount() + " members");
                    headerStatusLabel.setForeground(C_OFFLINE);
                    messagesPanel.removeAll(); messagesPanel.revalidate();
                    inputField.setEnabled(true);
                    sendButton.setEnabled(true);
                    inputField.requestFocusInWindow();
                    attachButton.setEnabled(true);
                    typingLabel.setText("");
                    refreshMembersPanel(room);
                    // Load history
                    sendFrame(ChatFrame.of(ChatFrame.Action.SEARCH_MESSAGES,
                            Map.of("query", "", "roomId", room.getId())));
                });
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty() || currentUser == null) return;

        // Cancel typing stop timer
        Optional.ofNullable(typingStopTask).ifPresent(f -> f.cancel(false));
        sendFrame(ChatFrame.of(ChatFrame.Action.TYPING_STOP,
                Map.of("roomId", activeRoomId != null ? activeRoomId : "")));

        if (inGroupMode && activeRoomId != null) {
            Message msg = Message.forRoom(currentUser.getUid(), activeRoomId, text);
            sendFrame(ChatFrame.of(ChatFrame.Action.SEND_GROUP_MSG, msg));
            appendMessage(msg);
        } else if (!inGroupMode && activePeerId != null) {
            Message msg = Message.of(currentUser.getUid(), activePeerId, text);
            sendFrame(ChatFrame.of(ChatFrame.Action.SEND_MESSAGE, msg));
            appendMessage(msg);
        }
        inputField.setText("");
    }

    private void sendTypingStart() {
        if (!inGroupMode || activeRoomId == null || currentUser == null) return;
        sendFrame(ChatFrame.of(ChatFrame.Action.TYPING_START,
                Map.of("roomId", activeRoomId)));
        // Auto-stop after 3s if user stops typing
        Optional.ofNullable(typingStopTask).ifPresent(f -> f.cancel(false));
        typingStopTask = typingDebounce.schedule(() ->
                sendFrame(ChatFrame.of(ChatFrame.Action.TYPING_STOP,
                        Map.of("roomId", activeRoomId))),
                3, TimeUnit.SECONDS);
    }

    private void openFileChooser() {
        if (activeRoomId == null && activePeerId == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Images, PDFs, Zip files (max 5 MB)",
                "jpg", "jpeg", "png", "gif", "webp", "pdf", "txt", "zip"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (file.length() > 5 * 1024 * 1024) {
                JOptionPane.showMessageDialog(this, "File exceeds 5 MB limit.", "Too large",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
                try {
                    byte[] data = Files.readAllBytes(file.toPath());
                    String b64  = Base64.getEncoder().encodeToString(data);
                    
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("fileName", file.getName());
                    payload.put("base64Data", b64);
                    payload.put("fileSize", file.length());
                    if (inGroupMode) {
                        payload.put("roomId", activeRoomId);
                    } else {
                        payload.put("peerId", activePeerId);
                    }

                    sendFrame(ChatFrame.of(ChatFrame.Action.UPLOAD_FILE, payload));
                    appendSystemMessage("Uploading: " + file.getName() + "…");
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(this,
                            "Could not read file: " + e.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
        }
    }

    private void performSearch() {
        String query = searchField.getText().trim();
        if (query.isBlank() || currentUser == null) return;
        Map<String, Object> payload = activeRoomId != null
                ? Map.of("query", query, "roomId", activeRoomId)
                : Map.of("query", query);
        sendFrame(ChatFrame.of(ChatFrame.Action.SEARCH_MESSAGES, payload));
    }

    private void refreshMembersPanel(Room room) {
        membersPanel.removeAll();
        boolean isAdmin = currentUser != null && room.isAdmin(currentUser.getUid());

        room.getMembers().forEach((uid, role) -> {
            JPanel memberRow = new JPanel(new BorderLayout(6, 0));
            memberRow.setBackground(new Color(248, 250, 252));
            memberRow.setBorder(new EmptyBorder(6, 12, 6, 12));
            memberRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

            // Resolve name: Check if it's current user or in DM list
            String displayName = uid;
            if (currentUser != null && uid.equals(currentUser.getUid())) {
                displayName = currentUser.getDisplayName() + " (You)";
            } else {
                for (int i = 0; i < dmListModel.size(); i++) {
                    User u = dmListModel.get(i);
                    if (u.getUid().equals(uid)) {
                        displayName = u.getDisplayName();
                        break;
                    }
                }
            }
            // Fallback to truncated UID if name not found
            if (displayName.equals(uid)) {
                displayName = uid.substring(0, Math.min(8, uid.length())) + "…";
            }

            JLabel nameLabel = new JLabel(displayName);
            nameLabel.setFont(F_SMALL);
            JLabel roleLabel = new JLabel(role.name());
            roleLabel.setFont(F_SMALL);
            roleLabel.setForeground(role == Room.Role.ADMIN ? C_ACCENT : C_OFFLINE);
            memberRow.add(nameLabel, BorderLayout.CENTER);
            memberRow.add(roleLabel, BorderLayout.EAST);

            // Admin can remove non-admin members
            if (isAdmin && role != Room.Role.ADMIN && currentUser != null
                    && !uid.equals(currentUser.getUid())) {
                JButton removeBtn = new JButton("x");
                removeBtn.setFont(F_SMALL);
                removeBtn.setForeground(Color.RED);
                removeBtn.setBackground(new Color(248, 250, 252));
                removeBtn.setBorderPainted(false);
                removeBtn.setFocusPainted(false);
                removeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                String targetUid = uid;
                removeBtn.addActionListener(e -> { // lambda
                    int confirm = JOptionPane.showConfirmDialog(this,
                            "Remove this user from the room?", "Confirm",
                            JOptionPane.YES_NO_OPTION);
                    if (confirm == JOptionPane.YES_OPTION) {
                        sendFrame(ChatFrame.of(ChatFrame.Action.REMOVE_USER,
                                Map.of("roomId", room.getId(), "targetUid", targetUid)));
                    }
                });
                memberRow.add(removeBtn, BorderLayout.WEST);
            }
            membersPanel.add(memberRow);
        });
        membersPanel.revalidate();
        membersPanel.repaint();
    }

    // ── Message bubble factory (pure function) ────────────────────────────────

    private JPanel createBubble(Message msg) {
        boolean isSent = currentUser != null
                && msg.getSenderId().equals(currentUser.getUid());
        boolean isBot  = msg.getType() == Message.MessageType.BOT;
        boolean isFile = msg.getType() == Message.MessageType.FILE;

        JPanel wrapper = new JPanel(new FlowLayout(
                isBot ? FlowLayout.CENTER : isSent ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
        wrapper.setBackground(C_BG);

        Color bubbleFill = isBot ? C_BOT : isSent ? C_SENT : C_RECEIVED;
        Color textColor  = isSent && !isBot ? Color.WHITE : new Color(30, 41, 59);
        Color timeColor  = isSent && !isBot ? new Color(167, 139, 250) : C_OFFLINE;

        JPanel bubble = new JPanel();
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBackground(bubbleFill);
        bubble.setBorder(new CompoundBorder(
                new LineBorder(isBot ? new Color(186, 230, 253) : isSent ? C_SENT
                        : new Color(226, 232, 240), 1, true),
                new EmptyBorder(8, 12, 8, 12)));
        bubble.setMaximumSize(new Dimension(440, Integer.MAX_VALUE));

        // Bot label
        if (isBot) {
            JLabel botTag = new JLabel("  Bot notification");
            botTag.setFont(F_SMALL);
            botTag.setForeground(new Color(3, 105, 161));
            bubble.add(botTag);
        }

        // File link or text content
        if (isFile) {
            msg.getFileUrl().ifPresent(url -> {
                JButton link = new JButton("Download: " + msg.getFileName().orElse("file"));
                link.setFont(F_SMALL);
                link.setForeground(isSent ? Color.WHITE : C_ACCENT);
                link.setBackground(bubbleFill);
                link.setBorderPainted(false);
                link.setFocusPainted(false);
                link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                link.addActionListener(e -> { // lambda
                    try { Desktop.getDesktop().browse(URI.create(url)); }
                    catch (Exception ex) { log.error("Open URL: {}", ex.getMessage()); }
                });
                bubble.add(link);
            });
        } else {
            JTextArea text = new JTextArea(msg.getContent());
            text.setFont(isBot ? F_MONO : F_BODY);
            text.setForeground(textColor);
            text.setBackground(bubbleFill);
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setBorder(null);
            text.setOpaque(false);
            bubble.add(text);
        }

        String timeStr = Instant.ofEpochMilli(msg.getTimestamp())
                .atZone(ZoneId.systemDefault()).format(TIME_FMT);
        JLabel timeLabel = new JLabel(timeStr);
        timeLabel.setFont(F_SMALL);
        timeLabel.setForeground(timeColor);
        timeLabel.setAlignmentX(isSent ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
        bubble.add(Box.createVerticalStrut(4));
        bubble.add(timeLabel);

        wrapper.add(bubble);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, wrapper.getPreferredSize().height));
        return wrapper;
    }

    private void appendMessage(Message msg) {
        JPanel bubble = bubbleFactory.apply(msg);
        messagesPanel.add(bubble);
        messagesPanel.add(Box.createVerticalStrut(8));
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }

    private void appendSystemMessage(String text) {
        JLabel label = new JLabel(text);
        label.setFont(F_SMALL);
        label.setForeground(C_OFFLINE);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        messagesPanel.add(label);
        messagesPanel.add(Box.createVerticalStrut(6));
        messagesPanel.revalidate();
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar sb = messagesScroll.getVerticalScrollBar();
            sb.setValue(sb.getMaximum());
        });
    }

    // ── WS send ───────────────────────────────────────────────────────────────

    private void sendFrame(ChatFrame frame) {
        if (webSocket == null || !connected) return;
        ChatFrameCodec.ENCODER.apply(frame)
                .ifPresent(json -> webSocket.sendText(json, true));
    }

    private void disconnect() {
        if (webSocket != null) webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void setConnectionStatus(boolean on) {
        statusLabel.setText(on ? "  Connected" : "  Disconnected");
        statusLabel.setForeground(on ? C_ONLINE : C_OFFLINE);
    }

    private JList<String> buildList(DefaultListModel<String> model) {
        JList<String> list = new JList<>(model);
        list.setBackground(C_SIDEBAR);
        list.setForeground(Color.WHITE);
        list.setFont(F_BODY);
        list.setSelectionBackground(C_HOVER);
        list.setCellRenderer(new SidebarCellRenderer());
        return list;
    }

    private JList<User> buildUserList(DefaultListModel<User> model) {
        JList<User> list = new JList<>(model);
        list.setBackground(C_SIDEBAR);
        list.setForeground(Color.WHITE);
        list.setFont(F_BODY);
        list.setSelectionBackground(C_HOVER);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof User u) {
                    setText("  " + u.getDisplayName() + (u.isOnline() ? " [online]" : " [offline]"));
                }
                return this;
            }
        });
        return list;
    }

    private JScrollPane noScrollBar(JList<?> list) {
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(null);
        sp.setBackground(C_SIDEBAR);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        return sp;
    }

    private JLabel sectionLabel(String text) {
        JLabel lbl = new JLabel("  " + text);
        lbl.setForeground(C_OFFLINE);
        lbl.setFont(F_SMALL);
        lbl.setBorder(new EmptyBorder(10, 0, 2, 0));
        return lbl;
    }

    private void addRow(JDialog dlg, GridBagConstraints c, String label,
                         JComponent field, int row) {
        c.gridx = 0; c.gridy = row * 2 - 1; c.gridwidth = 2;
        JLabel lbl = new JLabel(label); lbl.setFont(F_SMALL);
        dlg.add(lbl, c);
        c.gridy = row * 2; field.setFont(F_BODY);
        dlg.add(field, c);
    }

    private JButton styleBtn(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        btn.setFont(F_BOLD);
        btn.setPreferredSize(new Dimension(0, 36));
        return btn;
    }

    // ── Cell renderer ─────────────────────────────────────────────────────────

    private static final class SidebarCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean selected, boolean focused) {
            JLabel lbl = (JLabel) super.getListCellRendererComponent(
                    list, value, index, selected, focused);
            lbl.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            lbl.setBorder(new EmptyBorder(7, 16, 7, 16));
            lbl.setForeground(Color.WHITE);
            lbl.setBackground(selected ? new Color(55, 62, 90) : new Color(24, 28, 42));
            return lbl;
        }
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new ChatClientUI().setVisible(true);
        });
    }
}
