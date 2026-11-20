package dev.meeyah.burrow.session;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.net.RelayTunnel;
import dev.meeyah.burrow.state.FriendsState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Joiner side of Burrow: click a hosting friend, ask the Burrow server for
 * a relay room, build the tunnel, then let vanilla's own connect screen do
 * everything it normally does — our mixins quietly swap the transport to
 * the tunnel while the resolver reads a harmless 127.0.0.1.
 *
 * The active join is one-shot and expires: the Connection mixin consumes it
 * exactly once, and a join that never reaches a connect (cancelled screen)
 * can no longer hijack the next normal server connection after the window
 * passes.
 */
public final class JoinFlow {
    /** A join is only "active" for this long after join.ready. */
    private static final long ACTIVE_WINDOW_MS = 60_000;
    private static final long MISMATCH_TTL_MS = 30_000;

    public record RegistryMismatch(UUID hostUuid, List<String> namespaces, int missingCount, long recordedAtMs) {
        public boolean expired() {
            return System.currentTimeMillis() - recordedAtMs > MISMATCH_TTL_MS;
        }
    }

    private static volatile @Nullable ActiveJoin active;
    private static volatile @Nullable UUID lastConnectingHostUuid;
    private static volatile long lastConnectingAtMs;
    private static volatile @Nullable RegistryMismatch recentMismatch;
    private static volatile @Nullable UUID bypassMismatchHostUuid;
    private static volatile long bypassMismatchExpiresAtMs;

    private JoinFlow() {}

    /** Non-consuming peek: is there a live, unexpired, unused tunnel? */
    public static @Nullable RelayTunnel peekActive() {
        ActiveJoin join = active;
        return join != null && !join.consumed.get() && !join.expired() ? join.tunnel : null;
    }

    /**
     * If the local client is currently connected to a Burrow-hosted world
     * ({@code <hostUuid>.burrow[:port]}), returns that host's UUID; otherwise {@code null}.
     */
    public static @Nullable UUID currentBurrowHostUuid() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getSingleplayerServer() != null) {
            return null;
        }
        ServerData server = mc.getCurrentServer();
        if (server == null || server.ip == null) {
            return null;
        }
        String ip = server.ip.trim();
        int idx = ip.toLowerCase().indexOf(".burrow");
        if (idx <= 0) {
            return null;
        }
        try {
            return UUID.fromString(ip.substring(0, idx));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Returns the Burrow host UUID while either already in a {@code .burrow} world
     * or mid-handshake during configuration. Always returns {@code null} for
     * non-Burrow servers so external servers are never affected.
     */
    public static @Nullable UUID currentOrConnectingHostUuid() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getSingleplayerServer() != null) {
            return null;
        }
        ServerData server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return parseBurrowHostUuid(server.ip);
        }
        UUID pending = lastConnectingHostUuid;
        if (pending != null && System.currentTimeMillis() - lastConnectingAtMs <= ACTIVE_WINDOW_MS) {
            return pending;
        }
        return null;
    }

    private static @Nullable UUID parseBurrowHostUuid(String rawIp) {
        String ip = rawIp.trim();
        int idx = ip.toLowerCase().indexOf(".burrow");
        if (idx <= 0) {
            return null;
        }
        try {
            return UUID.fromString(ip.substring(0, idx));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static void recordRegistryMismatch(UUID hostUuid, List<String> namespaces, int missingCount) {
        if (hostUuid == null) return;
        recentMismatch = new RegistryMismatch(hostUuid, List.copyOf(namespaces), missingCount, System.currentTimeMillis());
    }

    public static @Nullable RegistryMismatch peekRecentRegistryMismatch() {
        RegistryMismatch m = recentMismatch;
        if (m == null || m.expired()) {
            recentMismatch = null;
            return null;
        }
        return m;
    }

    /** One-shot retry that strips unknown remote registry entries for {@code hostUuid}. */
    public static void retryIgnoringRegistryMismatch(UUID hostUuid) {
        if (hostUuid == null) return;
        recentMismatch = null;
        bypassMismatchHostUuid = hostUuid;
        bypassMismatchExpiresAtMs = System.currentTimeMillis() + ACTIVE_WINDOW_MS;
        beginByHostUuid(hostUuid);
    }

    public static boolean consumeRegistryMismatchBypass(@Nullable UUID hostUuid) {
        if (hostUuid == null) return false;
        UUID target = bypassMismatchHostUuid;
        if (target != null && target.equals(hostUuid) && System.currentTimeMillis() <= bypassMismatchExpiresAtMs) {
            bypassMismatchHostUuid = null;
            bypassMismatchExpiresAtMs = 0L;
            return true;
        }
        return false;
    }

    /** Consuming take, used by the Connection mixin exactly once per join. */
    public static @Nullable RelayTunnel consumeActive() {
        ActiveJoin join = active;
        if (join == null || join.consumed.getAndSet(true) || join.expired()) {
            return null;
        }
        return join.tunnel;
    }

    public static void begin(FriendsState.Friend host) {
        beginByHostUuid(host.uuid());
    }

    public static void beginByHostUuid(UUID hostUuid) {
        if (hostUuid != null && BurrowClient.connection() != null) {
            BurrowClient.friends().dismissInvite(hostUuid);
            BurrowClient.connection().joinBegin(hostUuid);
        }
    }

    /** Join a friend's public saved Minecraft server directly (bypassing Burrow relay). */
    public static void joinExternalServer(String serverName, String serverIp) {
        if (serverIp == null || serverIp.isBlank()) {
            return;
        }
        // Clear any unconsumed Burrow join so ConnectionMixin never intercepts this connect.
        ActiveJoin prev = active;
        active = null;
        lastConnectingHostUuid = null;
        recentMismatch = null;
        if (prev != null && !prev.consumed.get()) {
            prev.tunnel.close();
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            ServerAddress address = ServerAddress.parseString(serverIp);
            String label = (serverName == null || serverName.isBlank()) ? serverIp : serverName;
            ServerData data = new ServerData(label, serverIp, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(mc.screen, mc, address, data, false, null);
        });
    }

    public static void onJoinReady(UUID hostUuid, String worldName, String roomId, String token, String relayPath) {
        String hostName = lookupName(hostUuid);
        String url = HostSession.relayUrl(relayPath, roomId, token);
        Thread worker = new Thread(() -> {
            // The close handler only clears the active slot if this join is
            // still the current one — a newer join must never be clobbered.
            final ActiveJoin[] slot = new ActiveJoin[1];
            RelayTunnel tunnel = new RelayTunnel(RelayTunnel.Mode.JOIN, url, null, () -> {
                if (active == slot[0]) {
                    active = null;
                }
            });
            try {
                tunnel.connectBlocking(10);
            } catch (Exception e) {
                tunnel.close();
                BurrowClient.friends().setError("Couldn't connect to relay");
                return;
            }
            ActiveJoin join = new ActiveJoin(tunnel);
            slot[0] = join;
            ActiveJoin previous = active;
            active = join;
            if (previous != null && !previous.consumed.get()) {
                previous.tunnel.close();
            }
            Minecraft mc = Minecraft.getInstance();
            lastConnectingHostUuid = hostUuid;
            lastConnectingAtMs = System.currentTimeMillis();
            mc.execute(() -> {
                String fakeAddress = hostUuid + ".burrow";
                ServerAddress address = new ServerAddress(fakeAddress, 25565);
                ServerData data = new ServerData(
                        hostName == null ? "Friend's world" : hostName + " — " + worldName,
                        fakeAddress + ":" + 25565,
                        ServerData.Type.LAN);
                ConnectScreen.startConnecting(mc.screen, mc, address, data, false, null);
            });
        }, "burrow-join");
        worker.setDaemon(true);
        worker.start();
    }

    private static String lookupName(UUID uuid) {
        for (FriendsState.Friend f : BurrowClient.friends().friends()) {
            if (uuid.equals(f.uuid())) {
                return f.name();
            }
        }
        for (FriendsState.DiscoverableWorld d : BurrowClient.friends().discoverableWorlds()) {
            if (uuid.equals(d.host())) {
                return d.hostName();
            }
        }
        for (FriendsState.WorldInvite inv : BurrowClient.friends().worldInvites()) {
            if (uuid.equals(inv.host())) {
                return inv.hostName();
            }
        }
        return null;
    }

    private static final class ActiveJoin {
        final RelayTunnel tunnel;
        final long createdAt = System.currentTimeMillis();
        final AtomicBoolean consumed = new AtomicBoolean(false);

        ActiveJoin(RelayTunnel tunnel) {
            this.tunnel = tunnel;
        }

        boolean expired() {
            return System.currentTimeMillis() - createdAt > ACTIVE_WINDOW_MS;
        }
    }
}
