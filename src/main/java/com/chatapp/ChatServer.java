package com.chatapp;

import com.chatapp.config.AppConfig;
import com.chatapp.handler.WebSocketFrameHandler;
import com.chatapp.repository.FirebaseRepository;
import com.chatapp.service.*;
import com.chatapp.webhook.BotWebhookServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class ChatServer {

    private static final Logger log = LoggerFactory.getLogger(ChatServer.class);

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.get();

        // Dependency wiring (pure construction chain)
        FirebaseRepository repository     = new FirebaseRepository();
        PresenceService    presence        = new PresenceService(repository);
        AuthService        auth            = new AuthService(repository);
        GroupRoomService   groupRoom       = new GroupRoomService(repository, presence);
        MessageService     messages        = new MessageService(repository, presence);
        TypingService      typing          = new TypingService(repository, groupRoom);
        FileShareService   fileShare       = new FileShareService();
        RoleAccessService  roleAccess      = new RoleAccessService(groupRoom);

        // Preload data into caches
        presence.loadUsersFromDatabase()
                .thenRun(() -> log.info("Presence cache ready"))
                .exceptionally(ex -> { log.warn("Presence preload: {}", ex.getMessage()); return null; });

        groupRoom.loadRoomsFromDatabase()
                .thenRun(() -> log.info("Room cache ready"))
                .exceptionally(ex -> { log.warn("Room preload: {}", ex.getMessage()); return null; });

        // Shared WebSocket handler
        WebSocketFrameHandler wsHandler = new WebSocketFrameHandler(
                auth, messages, presence, groupRoom, typing, fileShare, roleAccess);

        // Webhook HTTP server
        BotWebhookServer webhookServer = new BotWebhookServer(groupRoom, messages, presence);
        webhookServer.start();

        // Netty event loop groups 
        EventLoopGroup bossGroup   = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new HttpServerCodec());
                            pipeline.addLast(new HttpObjectAggregator(10 * 1024 * 1024)); // 10 MB for file uploads
                            pipeline.addLast(new WebSocketServerCompressionHandler());
                            // Aggregate fragmented frames into full messages
                            pipeline.addLast(new io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator(10 * 1024 * 1024));
                            pipeline.addLast(new WebSocketServerProtocolHandler("/ws", null, true, 10 * 1024 * 1024));
                            pipeline.addLast(wsHandler);
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, config.getMaxConnections())
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true);

            Channel serverChannel = bootstrap.bind(config.getServerPort())
                    .sync().channel();

            log.info("╔══════════════════════════════════════════════════╗");
            log.info("║          FuncChat Pro — Server v2.0.0            ║");
            log.info("║  WebSocket : ws://localhost:{}/ws              ║", config.getServerPort());
            log.info("║  Webhook   : http://localhost:{}/webhook/{{roomId}} ║", config.getWebhookPort());
            log.info("╚══════════════════════════════════════════════════╝");

            // Graceful shutdown hook — lambda
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down FuncChat Pro...");
                typing.shutdown();
                fileShare.shutdown();
                webhookServer.stop();
                serverChannel.close();
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            }, "shutdown-hook"));

            serverChannel.closeFuture().sync();

        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
