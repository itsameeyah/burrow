package dev.meeyah.burrow.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One Burrow tunnel: a relay websocket bridged to one end of a netty local
 * channel pair. Only opaque bytes cross this bridge — vanilla Minecraft's
 * own framing, authentication and encryption ride on top untouched.
 *
 * HOST mode: on relay open, connects a LocalChannel to the integrated
 * server's private in-process endpoint (built from the captured vanilla
 * channel initializer).
 *
 * JOIN mode: binds a LocalServerChannel up front; the vanilla client —
 * redirected by our Connection mixin to local transport — connects to it,
 * and both sides pump bytes into the relay.
 */
public final class RelayTunnel {
    public enum Mode { HOST, JOIN }

    private final Mode mode;
    private final String relayUrl;
    private final @Nullable SocketAddress serverSideAddress;
    private final Runnable onClosed;
    private final AtomicReference<Channel> gameChannel = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile WebSocketClient webSocket;
    private volatile Channel localChannel;      // HOST: LocalChannel to server endpoint; JOIN: our LocalServerChannel
    private volatile LocalAddress joinAddress;  // JOIN only: where vanilla connects

    public RelayTunnel(Mode mode, String relayUrl, @Nullable SocketAddress serverSideAddress, Runnable onClosed) {
        this.mode = mode;
        this.relayUrl = relayUrl;
        this.serverSideAddress = serverSideAddress;
        this.onClosed = onClosed;

        if (mode == Mode.JOIN) {
            // Bind our half before anything else so the vanilla client's
            // (soon-to-be redirected) connect always finds a listener.
            this.joinAddress = new LocalAddress("burrow-join-" + UUID.randomUUID());
            this.localChannel = new ServerBootstrap()
                    .group(EventLoopGroupHolder.local().eventLoopGroup())
                    .channel(LocalServerChannel.class)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new GameSideHandler(RelayTunnel.this));
                        }
                    })
                    .localAddress(this.joinAddress)
                    .bind()
                    .syncUninterruptibly()
                    .channel();
        }
    }

    /** The address vanilla's mixin-redirected connect should target (JOIN). */
    public LocalAddress gameSideAddress() {
        return this.joinAddress;
    }

    public void connectBlocking(int timeoutSeconds) throws InterruptedException {
        WebSocketClient ws = new WebSocketClient(URI.create(this.relayUrl)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                onRelayOpen();
            }

            @Override
            public void onMessage(String message) {
                // The relay speaks binary only; stray text is ignored.
            }

            @Override
            public void onMessage(ByteBuffer bytes) {
                Channel game = RelayTunnel.this.gameChannel.get();
                if (game != null && game.isActive()) {
                    byte[] data = new byte[bytes.remaining()];
                    bytes.get(data);
                    game.writeAndFlush(Unpooled.wrappedBuffer(data));
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                RelayTunnel.this.shutdown();
            }

            @Override
            public void onError(Exception ex) {
                // Handled through onClose.
            }
        };
        this.webSocket = ws;
        if (!ws.connectBlocking(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new InterruptedException("relay connect timed out");
        }
    }

    public boolean isClosed() {
        return this.closed.get();
    }

    public void close() {
        shutdown();
    }

    private void onRelayOpen() {
        if (this.mode != Mode.HOST || this.serverSideAddress == null) {
            return;
        }
        this.localChannel = new Bootstrap()
                .group(EventLoopGroupHolder.local().eventLoopGroup())
                .channel(LocalChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new GameSideHandler(RelayTunnel.this));
                    }
                })
                .connect(this.serverSideAddress)
                .syncUninterruptibly()
                .channel();
    }

    private void shutdown() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        WebSocketClient ws = this.webSocket;
        if (ws != null) {
            ws.close();
        }
        Channel game = this.gameChannel.get();
        if (game != null) {
            game.close();
        }
        Channel local = this.localChannel;
        if (local != null) {
            local.close();
        }
        this.onClosed.run();
    }

    /** One direction of the byte pump: netty local channel -> relay ws. */
    private static final class GameSideHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final RelayTunnel tunnel;

        GameSideHandler(RelayTunnel tunnel) {
            this.tunnel = tunnel;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            this.tunnel.gameChannel.set(ctx.channel());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            WebSocketClient ws = this.tunnel.webSocket;
            if (ws != null && ws.isOpen()) {
                ws.send(data);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            this.tunnel.shutdown();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
