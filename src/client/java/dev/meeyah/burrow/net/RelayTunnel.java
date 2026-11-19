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

import java.net.SocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *
 * Early relay frames that arrive before the game-side channel is active
 * are buffered (bounded) and flushed in strict FIFO order on channelActive,
 * so the joiner's first handshake packet can never be dropped or reordered.
 */
public final class RelayTunnel {
    public enum Mode { HOST, JOIN }

    /** Prefix for join-side local addresses; ConnectionMemoryMixin matches on it. */
    public static final String JOIN_ADDRESS_PREFIX = "burrow-join-";

    private static final int MAX_PENDING_FRAMES = 512;

    private final Mode mode;
    private final String relayUrl;
    private final @Nullable SocketAddress serverSideAddress;
    private final Runnable onClosed;
    /** Guards gameChannel + pendingFromRelay so early frames are strictly ordered. */
    private final Object stateLock = new Object();
    private @Nullable Channel gameChannel;
    private final Deque<byte[]> pendingFromRelay = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile WebSocketClient webSocket;
    private volatile Channel localChannel;      // HOST: LocalChannel to server endpoint; JOIN: our LocalServerChannel
    private volatile LocalAddress joinAddress;   // JOIN only: where vanilla connects

    public RelayTunnel(Mode mode, String relayUrl, @Nullable SocketAddress serverSideAddress, Runnable onClosed) {
        this.mode = mode;
        this.relayUrl = relayUrl;
        this.serverSideAddress = serverSideAddress;
        this.onClosed = onClosed;

        if (mode == Mode.JOIN) {
            // Bind our half before anything else so the vanilla client's
            // (soon-to-be redirected) connect always finds a listener.
            this.joinAddress = new LocalAddress(JOIN_ADDRESS_PREFIX + UUID.randomUUID());
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
        URI uri;
        try {
            uri = URI.create(this.relayUrl);
        } catch (Exception e) {
            shutdown();
            throw new IllegalArgumentException("invalid relay URL: " + this.relayUrl, e);
        }
        WebSocketClient ws = new WebSocketClient(uri) {
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
                if (RelayTunnel.this.closed.get()) {
                    return;
                }
                byte[] data = new byte[bytes.remaining()];
                bytes.get(data);
                Channel target = null;
                boolean overflow = false;
                synchronized (RelayTunnel.this.stateLock) {
                    Channel ch = RelayTunnel.this.gameChannel;
                    if (ch != null) {
                        target = ch;
                    } else if (RelayTunnel.this.pendingFromRelay.size() < MAX_PENDING_FRAMES) {
                        RelayTunnel.this.pendingFromRelay.addLast(data);
                    } else {
                        overflow = true;
                    }
                }
                if (overflow) {
                    // Never silently drop bytes from an encrypted TCP stream.
                    RelayTunnel.this.shutdown();
                } else if (target != null && target.isActive()) {
                    target.writeAndFlush(Unpooled.wrappedBuffer(data));
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
            shutdown();
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
        try {
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
        } catch (Throwable t) {
            // If the local endpoint vanished (host stopped hosting mid-handshake),
            // tear the tunnel down instead of leaking a half-open relay socket.
            shutdown();
        }
    }

    private void shutdown() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        WebSocketClient ws = this.webSocket;
        if (ws != null) {
            ws.close();
        }
        Channel game;
        synchronized (this.stateLock) {
            game = this.gameChannel;
            this.gameChannel = null;
            this.pendingFromRelay.clear();
        }
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
            if (this.tunnel.closed.get()) {
                ctx.close();
                return;
            }
            // Drain pending frames in strict FIFO order on the Netty event loop
            // BEFORE publishing gameChannel so concurrent onMessage writes are
            // guaranteed to queue after every buffered frame.
            boolean wrote = false;
            synchronized (this.tunnel.stateLock) {
                byte[] frame;
                while ((frame = this.tunnel.pendingFromRelay.pollFirst()) != null) {
                    ctx.write(Unpooled.wrappedBuffer(frame));
                    wrote = true;
                }
                this.tunnel.gameChannel = ctx.channel();
            }
            if (wrote) {
                ctx.flush();
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            WebSocketClient ws = this.tunnel.webSocket;
            if (ws != null && ws.isOpen()) {
                try {
                    ws.send(data);
                } catch (Throwable ignored) {
                    this.tunnel.shutdown();
                }
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
