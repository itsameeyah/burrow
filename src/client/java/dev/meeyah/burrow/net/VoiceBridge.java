package dev.meeyah.burrow.net;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.session.HostWorldState;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transport bridge for auxiliary UDP voice tunnels (e.g. Simple Voice Chat)
 * riding over Burrow's binary WebSocket relay.
 *
 * This class intentionally has ZERO compile-time or runtime references to
 * Simple Voice Chat so Burrow remains completely standalone when SVC is not
 * installed.
 */
public final class VoiceBridge {
    private static final Logger LOG = LoggerFactory.getLogger("Burrow");
    private static final int MAX_UDP_PACKET = 4096;

    public record RelayTarget(String url) {}

    private static volatile int hostVoicePort = -1;
    private static final Map<String, HostVoiceTunnel> HOST_TUNNELS = new ConcurrentHashMap<>();
    private static final Map<UUID, HostVoiceTunnel> HOST_TUNNELS_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, Queue<CompletableFuture<RelayTarget>>> PENDING_CLIENT_REQUESTS =
            new ConcurrentHashMap<>();

    private VoiceBridge() {}

    public static void setHostVoicePort(int port) {
        if (port > 0) {
            hostVoicePort = port;
            LOG.info("Burrow voice bridge detected local voice server on UDP port {}", port);
        }
    }

    public static void clearHostVoicePortIf(int port) {
        if (port > 0 && hostVoicePort == port) {
            hostVoicePort = -1;
        }
    }

    public static int hostVoicePort() {
        return hostVoicePort;
    }

    /** Joiner side: ask signaling server for a dedicated voice relay room. */
    public static CompletableFuture<RelayTarget> requestVoiceRelay(UUID hostUuid) {
        CompletableFuture<RelayTarget> future = new CompletableFuture<>();
        BurrowConnection conn = BurrowClient.connection();
        if (hostUuid == null || conn == null || !conn.voiceBegin(hostUuid)) {
            future.completeExceptionally(new IllegalStateException("Not connected to Burrow signaling server"));
            return future;
        }
        PENDING_CLIENT_REQUESTS
                .computeIfAbsent(hostUuid, k -> new ConcurrentLinkedQueue<>())
                .add(future);
        return future;
    }

    /** Joiner side: signaling replied with voice.ready room credentials. */
    public static void onVoiceReady(
            @Nullable UUID hostUuid,
            @Nullable String roomId,
            @Nullable String token,
            @Nullable String relayPath
    ) {
        if (hostUuid == null || roomId == null || token == null || relayPath == null) {
            return;
        }
        Queue<CompletableFuture<RelayTarget>> q = PENDING_CLIENT_REQUESTS.get(hostUuid);
        if (q == null) {
            return;
        }
        CompletableFuture<RelayTarget> next;
        while ((next = q.poll()) != null) {
            if (!next.isDone()) {
                next.complete(new RelayTarget(HostSession.relayUrl(relayPath, roomId, token)));
                return;
            }
        }
    }

    /** Host side: signaling told us a joiner opened a voice relay room. */
    public static void onRelayVoice(
            @Nullable String roomId,
            @Nullable String token,
            @Nullable String relayPath,
            @Nullable UUID joinerUuid
    ) {
        if (roomId == null || token == null || relayPath == null) {
            return;
        }
        if (!HostSession.isHosting()) {
            return;
        }
        int port = hostVoicePort;
        if (port <= 0) {
            LOG.debug("Ignoring relay.voice for room {}: no local voice server port registered", roomId);
            return;
        }
        HostWorldState state = HostSession.worldState();
        if (state != null && joinerUuid != null && state.isBanned(joinerUuid)) {
            return;
        }

        String url = HostSession.relayUrl(relayPath, roomId, token);
        Thread worker = new Thread(() -> {
            HostVoiceTunnel tunnel = new HostVoiceTunnel(url, port, () -> {
                HOST_TUNNELS.remove(roomId);
                if (joinerUuid != null) {
                    HOST_TUNNELS_BY_PLAYER.remove(joinerUuid);
                }
            });
            HOST_TUNNELS.put(roomId, tunnel);
            if (joinerUuid != null) {
                HostVoiceTunnel prev = HOST_TUNNELS_BY_PLAYER.put(joinerUuid, tunnel);
                if (prev != null && prev != tunnel) {
                    prev.close();
                }
            }
            try {
                tunnel.start(10);
            } catch (Throwable t) {
                LOG.warn("Failed to establish host voice tunnel for room {}: {}", roomId, t.toString());
                tunnel.close();
                HOST_TUNNELS.remove(roomId);
                if (joinerUuid != null) {
                    HOST_TUNNELS_BY_PLAYER.remove(joinerUuid, tunnel);
                }
            }
        }, "burrow-voice-host");
        worker.setDaemon(true);
        worker.start();
    }

    public static void closeAllHostTunnels() {
        for (HostVoiceTunnel tunnel : HOST_TUNNELS.values()) {
            tunnel.close();
        }
        HOST_TUNNELS.clear();
        HOST_TUNNELS_BY_PLAYER.clear();
    }

    public static void closeHostTunnelForPlayer(@Nullable UUID uuid) {
        if (uuid == null) return;
        HostVoiceTunnel tunnel = HOST_TUNNELS_BY_PLAYER.remove(uuid);
        if (tunnel != null) {
            tunnel.close();
        }
    }

    /**
     * One host-side UDP <-> WebSocket relay bridge. Each joining player gets
     * their own ephemeral local DatagramSocket talking to 127.0.0.1:<voicePort>,
     * so Simple Voice Chat sees each joiner as a distinct UDP client address.
     */
    private static final class HostVoiceTunnel {
        private final String relayUrl;
        private final int targetPort;
        private final Runnable onClosed;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile DatagramSocket udpSocket;
        private volatile WebSocketClient webSocket;

        HostVoiceTunnel(String relayUrl, int targetPort, Runnable onClosed) {
            this.relayUrl = relayUrl;
            this.targetPort = targetPort;
            this.onClosed = onClosed;
        }

        void start(int timeoutSeconds) throws Exception {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            InetSocketAddress targetAddr = new InetSocketAddress(loopback, this.targetPort);
            DatagramSocket udp = new DatagramSocket(0, loopback);
            this.udpSocket = udp;

            URI uri = URI.create(this.relayUrl);
            WebSocketClient ws = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    startUdpReader(udp, this);
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
                    try {
                        udp.send(new DatagramPacket(data, data.length, targetAddr));
                    } catch (Exception e) {
                        if (!closed.get()) {
                            close();
                        }
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    HostVoiceTunnel.this.close();
                }

                @Override
                public void onError(Exception ex) {
                    LOG.debug("Host voice relay socket error: {}", ex.toString());
                }
            };
            ws.setDaemon(true);
            this.webSocket = ws;

            if (!ws.connectBlocking(timeoutSeconds, TimeUnit.SECONDS)) {
                close();
                throw new IllegalStateException("Host voice relay connect timed out");
            }
        }

        private void startUdpReader(DatagramSocket udp, WebSocketClient ws) {
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[MAX_UDP_PACKET];
                while (!closed.get() && !udp.isClosed() && ws.isOpen()) {
                    try {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        udp.receive(pkt);
                        byte[] payload = new byte[pkt.getLength()];
                        System.arraycopy(pkt.getData(), pkt.getOffset(), payload, 0, pkt.getLength());
                        if (ws.isOpen()) {
                            ws.send(payload);
                        }
                    } catch (Exception e) {
                        break;
                    }
                }
                close();
            }, "burrow-voice-host-rx");
            reader.setDaemon(true);
            reader.start();
        }

        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            DatagramSocket udp = this.udpSocket;
            if (udp != null) {
                udp.close();
            }
            WebSocketClient ws = this.webSocket;
            if (ws != null) {
                ws.close();
            }
            this.onClosed.run();
        }
    }
}
