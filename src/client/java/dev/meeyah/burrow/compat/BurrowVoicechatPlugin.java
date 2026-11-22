package dev.meeyah.burrow.compat;

import de.maxhenkel.voicechat.api.ClientVoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatSocket;
import de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoiceHostEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartingEvent;
import dev.meeyah.burrow.net.VoiceBridge;
import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.session.JoinFlow;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional Simple Voice Chat plugin (loaded only when SVC is installed).
 *
 * Host side:
 * - Captures the integrated server's bound UDP voice port (including after
 *   LAN publish port changes) so {@link VoiceBridge} can forward remote
 *   Burrow joiners' UDP datagrams straight to {@code 127.0.0.1:<port>}.
 * - Sets {@code voiceHost = "127.0.0.1"} while Burrow is hosting so remote
 *   joiners don't fail DNS resolution on {@code <uuid>.burrow}.
 *
 * Joiner side:
 * - When connected to a {@code .burrow} world, replaces SVC's client UDP
 *   socket with a WebSocket-tunneled socket backed by a dedicated Burrow
 *   voice relay room. Non-Burrow servers and singleplayer are untouched.
 */
public final class BurrowVoicechatPlugin implements VoicechatPlugin {
    private static final Logger LOG = LoggerFactory.getLogger("Burrow");

    @Override
    public String getPluginId() {
        return "burrow";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartingEvent.class, this::onServerStarting);
        registration.registerEvent(VoiceHostEvent.class, this::onVoiceHost);
        registration.registerEvent(ClientVoicechatInitializationEvent.class, this::onClientInit);
    }

    private void onServerStarting(VoicechatServerStartingEvent event) {
        VoicechatSocket existing = event.getSocketImplementation();
        event.setSocketImplementation(new PortTrackingServerSocket(existing));
    }

    private void onVoiceHost(VoiceHostEvent event) {
        if (!HostSession.isHosting()) {
            return;
        }
        String current = event.getVoiceHost();
        if (current == null || current.isEmpty()) {
            // Prevents joiners from trying to DNS-resolve "<uuid>.burrow" before
            // ClientVoicechatInitializationEvent installs our tunneled socket,
            // while keeping the host's own local client on 127.0.0.1 working natively.
            event.setVoiceHost("127.0.0.1");
        }
    }

    private void onClientInit(ClientVoicechatInitializationEvent event) {
        UUID hostUuid = JoinFlow.currentBurrowHostUuid();
        if (hostUuid == null) {
            return;
        }
        LOG.info("Installing Burrow voice tunnel socket for hosted world {}", hostUuid);
        event.setSocketImplementation(new BurrowClientVoicechatSocket(hostUuid));
    }

    private record SimpleUdpPacket(byte[] data, SocketAddress address, long timestamp) implements RawUdpPacket {
        @Override
        public byte[] getData() {
            return this.data;
        }

        @Override
        public long getTimestamp() {
            return this.timestamp;
        }

        @Override
        public SocketAddress getSocketAddress() {
            return this.address;
        }
    }

    /**
     * Wraps an existing VoicechatSocket (if another plugin set one) or provides
     * a standard DatagramSocket implementation, recording the bound UDP port
     * for {@link VoiceBridge}.
     */
    private static final class PortTrackingServerSocket implements VoicechatSocket {
        private final @Nullable VoicechatSocket delegate;
        private final byte[] buffer = new byte[4096];
        private volatile @Nullable DatagramSocket socket;
        private volatile int boundPort = -1;

        PortTrackingServerSocket(@Nullable VoicechatSocket delegate) {
            this.delegate = delegate;
        }

        @Override
        public void open(int port, String bindAddress) throws Exception {
            if (this.delegate != null) {
                this.delegate.open(port, bindAddress);
                this.boundPort = this.delegate.getLocalPort();
                VoiceBridge.setHostVoicePort(this.boundPort);
                return;
            }
            if (this.socket != null) {
                throw new IllegalStateException("Socket already opened");
            }
            InetAddress addr = null;
            if (bindAddress != null && !bindAddress.isEmpty()) {
                try {
                    addr = InetAddress.getByName(bindAddress);
                } catch (Exception e) {
                    LOG.warn("Voice chat bind address '{}' could not be parsed; falling back to wildcard", bindAddress);
                }
            }
            try {
                this.socket = new DatagramSocket(port, addr);
            } catch (Exception e) {
                if (addr != null && !"0.0.0.0".equals(bindAddress)) {
                    this.socket = new DatagramSocket(port);
                } else {
                    throw e;
                }
            }
            this.boundPort = this.socket.getLocalPort();
            VoiceBridge.setHostVoicePort(this.boundPort);
        }

        @Override
        public RawUdpPacket read() throws Exception {
            if (this.delegate != null) {
                return this.delegate.read();
            }
            DatagramSocket s = this.socket;
            if (s == null || s.isClosed()) {
                throw new SocketException("Socket closed");
            }
            DatagramPacket pkt = new DatagramPacket(this.buffer, this.buffer.length);
            s.receive(pkt);
            long ts = System.currentTimeMillis();
            byte[] data = new byte[pkt.getLength()];
            System.arraycopy(pkt.getData(), pkt.getOffset(), data, 0, pkt.getLength());
            return new SimpleUdpPacket(data, pkt.getSocketAddress(), ts);
        }

        @Override
        public void send(byte[] data, SocketAddress address) throws Exception {
            if (this.delegate != null) {
                this.delegate.send(data, address);
                return;
            }
            DatagramSocket s = this.socket;
            if (s == null || s.isClosed()) {
                return;
            }
            s.send(new DatagramPacket(data, data.length, address));
        }

        @Override
        public int getLocalPort() {
            if (this.delegate != null) {
                return this.delegate.getLocalPort();
            }
            DatagramSocket s = this.socket;
            return s == null ? -1 : s.getLocalPort();
        }

        @Override
        public void close() {
            VoiceBridge.clearHostVoicePortIf(this.boundPort);
            if (this.delegate != null) {
                this.delegate.close();
                return;
            }
            DatagramSocket s = this.socket;
            if (s != null) {
                s.close();
            }
        }

        @Override
        public boolean isClosed() {
            if (this.delegate != null) {
                return this.delegate.isClosed();
            }
            DatagramSocket s = this.socket;
            return s == null || s.isClosed();
        }
    }

    /**
     * Joiner-side ClientVoicechatSocket tunneled through a Burrow binary
     * WebSocket relay room. Connects asynchronously on {@link #open()} so
     * Minecraft's render/client threads never block.
     */
    private static final class BurrowClientVoicechatSocket implements ClientVoicechatSocket {
        private static final int MAX_RX_QUEUE = 256;
        private static final int MAX_PRE_OPEN_TX = 4;
        private static final RawUdpPacket POISON = new SimpleUdpPacket(
                new byte[0], new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0L);

        private final UUID hostUuid;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final BlockingQueue<RawUdpPacket> rxQueue = new LinkedBlockingQueue<>(MAX_RX_QUEUE);
        private final Object txLock = new Object();
        private final Deque<byte[]> preOpenTx = new ArrayDeque<>(MAX_PRE_OPEN_TX);
        private volatile SocketAddress remoteAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        private volatile @Nullable WebSocketClient webSocket;
        private volatile @Nullable CompletableFuture<VoiceBridge.RelayTarget> pendingTarget;

        BurrowClientVoicechatSocket(UUID hostUuid) {
            this.hostUuid = hostUuid;
        }

        @Override
        public void open() {
            Thread connector = new Thread(() -> {
                try {
                    CompletableFuture<VoiceBridge.RelayTarget> fut = VoiceBridge.requestVoiceRelay(this.hostUuid);
                    this.pendingTarget = fut;
                    VoiceBridge.RelayTarget target = fut.get(10, TimeUnit.SECONDS);
                    if (this.closed.get()) {
                        return;
                    }
                    WebSocketClient ws = new WebSocketClient(URI.create(target.url())) {
                        @Override
                        public void onOpen(ServerHandshake handshake) {
                            synchronized (txLock) {
                                byte[] pending;
                                while ((pending = preOpenTx.pollFirst()) != null) {
                                    try {
                                        send(pending);
                                    } catch (Exception ignored) {
                                        break;
                                    }
                                }
                            }
                        }

                        @Override
                        public void onMessage(String message) {
                            // Binary frames only.
                        }

                        @Override
                        public void onMessage(ByteBuffer bytes) {
                            if (closed.get()) return;
                            byte[] data = new byte[bytes.remaining()];
                            bytes.get(data);
                            RawUdpPacket pkt = new SimpleUdpPacket(data, remoteAddress, System.currentTimeMillis());
                            if (!rxQueue.offer(pkt)) {
                                // Drop oldest frame rather than introducing voice latency.
                                rxQueue.poll();
                                rxQueue.offer(pkt);
                            }
                        }

                        @Override
                        public void onClose(int code, String reason, boolean remote) {
                            BurrowClientVoicechatSocket.this.close();
                        }

                        @Override
                        public void onError(Exception ex) {
                            LOG.debug("Burrow client voice socket error: {}", ex.toString());
                        }
                    };
                    ws.setDaemon(true);
                    ws.setTcpNoDelay(true);
                    this.webSocket = ws;
                    if (!ws.connectBlocking(10, TimeUnit.SECONDS)) {
                        LOG.warn("Timed out connecting to Burrow voice relay for host {}", this.hostUuid);
                        close();
                    }
                } catch (Exception e) {
                    if (!this.closed.get()) {
                        LOG.warn("Could not open Burrow voice tunnel for host {}: {}", this.hostUuid, e.toString());
                        close();
                    }
                }
            }, "burrow-voice-client");
            connector.setDaemon(true);
            connector.start();
        }

        @Override
        public RawUdpPacket read() throws Exception {
            if (this.closed.get()) {
                throw new SocketException("Socket closed");
            }
            RawUdpPacket pkt = this.rxQueue.take();
            if (pkt == POISON || this.closed.get()) {
                throw new SocketException("Socket closed");
            }
            return pkt;
        }

        @Override
        public void send(byte[] data, SocketAddress address) throws Exception {
            if (this.closed.get()) {
                throw new SocketException("Socket closed");
            }
            if (address != null) {
                this.remoteAddress = address;
            }
            WebSocketClient ws = this.webSocket;
            if (ws != null && ws.isOpen()) {
                try {
                    ws.send(data);
                } catch (Exception e) {
                    if (this.closed.get()) {
                        throw new SocketException("Socket closed");
                    }
                }
                return;
            }
            synchronized (this.txLock) {
                WebSocketClient current = this.webSocket;
                if (current != null && current.isOpen()) {
                    current.send(data);
                } else {
                    if (this.preOpenTx.size() >= MAX_PRE_OPEN_TX) {
                        this.preOpenTx.pollFirst();
                    }
                    this.preOpenTx.addLast(data);
                }
            }
        }

        @Override
        public void close() {
            if (!this.closed.compareAndSet(false, true)) {
                return;
            }
            CompletableFuture<VoiceBridge.RelayTarget> fut = this.pendingTarget;
            if (fut != null) {
                fut.cancel(true);
            }
            WebSocketClient ws = this.webSocket;
            if (ws != null) {
                ws.close();
            }
            synchronized (this.txLock) {
                this.preOpenTx.clear();
            }
            this.rxQueue.clear();
            this.rxQueue.offer(POISON);
        }

        @Override
        public boolean isClosed() {
            return this.closed.get();
        }
    }
}
