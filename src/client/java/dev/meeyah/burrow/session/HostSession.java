package dev.meeyah.burrow.session;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.compat.ModCompat;
import dev.meeyah.burrow.net.BurrowConnection;
import dev.meeyah.burrow.net.RelayTunnel;
import dev.meeyah.burrow.net.VoiceBridge;
import dev.meeyah.burrow.state.ActivityTracker;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.jetbrains.annotations.Nullable;

import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Host side of Burrow: shares the current singleplayer world with friends
 * (or invitees / friends-of-friends / join-code guests) and owns the per-world
 * {@link HostWorldState} (access mode, lock, cap, roles, bans, join sources).
 */
public final class HostSession {
    /** Local address id of the private host endpoint; the auth mixin matches on it. */
    public static final String HOST_ADDRESS_ID = "burrow-host";

    private static volatile @Nullable ChannelHandler capturedInitializer;
    private static volatile @Nullable Channel localServer;
    private static volatile @Nullable SocketAddress localEndpoint;
    private static volatile @Nullable MinecraftServer stateServer;
    private static volatile @Nullable HostWorldState currentState;
    private static volatile boolean hosting;
    private static volatile @Nullable String joinCode;
    /** One tunnel per relay room (one per joining friend). */
    private static final Map<String, RelayTunnel> TUNNELS = new ConcurrentHashMap<>();
    /** Latest tunnel per joiner UUID so kick/ban can immediately sever their relay socket. */
    private static final Map<UUID, RelayTunnel> TUNNELS_BY_PLAYER = new ConcurrentHashMap<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private HostSession() {}

    public static void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    public static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    private static void notifyListeners() {
        List<Runnable> snapshot = List.copyOf(LISTENERS);
        if (snapshot.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                for (Runnable r : snapshot) r.run();
            });
        } else {
            for (Runnable r : snapshot) r.run();
        }
    }

    /** Called by our ServerConnectionListener mixin whenever vanilla binds a server channel. */
    public static void rememberInitializer(ChannelHandler initializer) {
        capturedInitializer = initializer;
    }

    public static boolean isHosting() {
        return hosting;
    }

    public static @Nullable String joinCode() {
        return hosting ? joinCode : null;
    }

    /** Vanilla Minecraft IP address for a 6-char join code, e.g. {@code ABC123.burrow.meeyah.dev}. */
    public static String joinCodeAddress(String code) {
        String host = "burrow.meeyah.dev";
        try {
            URI uri = URI.create(BurrowClient.relayBase());
            if (uri.getHost() != null && !uri.getHost().isBlank()) {
                host = uri.getHost();
            }
        } catch (Exception ignored) {
        }
        return code.toUpperCase() + "." + host;
    }

    public static void generateJoinCode() {
        if (!hosting) return;
        BurrowConnection conn = BurrowClient.connection();
        if (conn != null) conn.sessionCode("generate");
    }

    public static void regenerateJoinCode() {
        if (!hosting) return;
        BurrowConnection conn = BurrowClient.connection();
        if (conn != null) conn.sessionCode("regenerate");
    }

    public static void disableJoinCode() {
        joinCode = null;
        BurrowConnection conn = BurrowClient.connection();
        if (hosting && conn != null) {
            conn.sessionCode("disable");
        }
        notifyListeners();
    }

    public static void onJoinCodeState(@Nullable String code, boolean enabled) {
        joinCode = (hosting && enabled && code != null && !code.isBlank()) ? code.trim().toUpperCase() : null;
        notifyListeners();
    }

    /** Lazy-loaded per-world HostWorldState for the active integrated server. */
    public static synchronized @Nullable HostWorldState worldState(@Nullable MinecraftServer server) {
        if (server == null) {
            return null;
        }
        if (stateServer != server || currentState == null) {
            stateServer = server;
            currentState = HostWorldState.load(server);
        }
        return currentState;
    }

    public static synchronized @Nullable HostWorldState worldState() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? null : worldState(mc.getSingleplayerServer());
    }

    public static synchronized void start() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        BurrowConnection connection = BurrowClient.connection();
        if (server == null || hosting || connection == null) {
            return;
        }
        HostWorldState state = worldState(server);
        if (state == null) {
            return;
        }

        // Publish to LAN if needed so the game creates its real server
        // channel — and our mixin captures the initializer during the bind.
        if (!server.isPublished() && capturedInitializer == null) {
            server.publishServer(server.getDefaultGameType(), false, 0);
        }
        ChannelHandler initializer = capturedInitializer;
        if (initializer == null) {
            return;
        }

        if (localServer == null || !localServer.isActive()) {
            localServer = new ServerBootstrap()
                    .group(EventLoopGroupHolder.local().eventLoopGroup())
                    .channel(LocalServerChannel.class)
                    .childHandler(initializer)
                    .localAddress(new LocalAddress(HOST_ADDRESS_ID))
                    .bind()
                    .syncUninterruptibly()
                    .channel();
        }
        localEndpoint = localServer.localAddress();

        hosting = true;
        joinCode = null;
        sendSession(connection, server, state, true, Math.max(1, server.getPlayerCount()), null);
        notifyListeners();
    }

    /**
     * Invite a friend into the current singleplayer world. If the world is not
     * hosted yet, automatically starts hosting first so inviting from the
     * Friends list works in one click.
     */
    public static synchronized boolean inviteFriend(UUID targetUuid) {
        if (targetUuid == null) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc == null ? null : mc.getSingleplayerServer();
        BurrowConnection connection = BurrowClient.connection();
        if (server == null || connection == null) {
            return false;
        }
        if (!hosting) {
            start();
        }
        if (!hosting) {
            return false;
        }
        connection.sessionInvite(targetUuid, null);
        BurrowClient.friends().dismissInviteRequest(targetUuid);
        return true;
    }

    public static synchronized void stop() {
        joinCode = null;
        if (!hosting) {
            closeTunnels();
            closeLocalServer();
            notifyListeners();
            return;
        }
        hosting = false;
        HostWorldState state = currentState;
        if (state != null) {
            state.setLocked(false);
        }
        closeTunnels();
        closeLocalServer();

        BurrowConnection connection = BurrowClient.connection();
        if (connection != null) {
            connection.sessionClose();
        }
        ActivityTracker.refreshDiscord();
        notifyListeners();
    }

    /** Push updated settings/lock/bans/roles/player count to the Burrow server if hosting. */
    public static synchronized void syncState() {
        if (!hosting) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc == null ? null : mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        syncStateWithCount(Math.max(1, server.getPlayerCount()), null);
    }

    public static synchronized void syncStateWithCount(int playerCount) {
        syncStateWithCount(playerCount, null);
    }

    public static synchronized void syncStateWithCount(int playerCount, @Nullable UUID excludingPlayer) {
        if (!hosting) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc == null ? null : mc.getSingleplayerServer();
        BurrowConnection connection = BurrowClient.connection();
        HostWorldState state = worldState(server);
        if (server == null || connection == null || state == null) {
            return;
        }
        sendSession(connection, server, state, false, Math.max(1, playerCount), excludingPlayer);
    }

    /** Belt-and-braces cleanup when the singleplayer world itself goes away. */
    public static synchronized void ensureStopped() {
        if (hosting) {
            stop();
        } else {
            joinCode = null;
            closeTunnels();
            closeLocalServer();
        }
    }

    /** Called on ServerLifecycleEvents.SERVER_STOPPING so stale state never leaks across worlds. */
    public static synchronized void onServerStopping() {
        ensureStopped();
        ModCompat.clearAllSessions();
        capturedInitializer = null;
        stateServer = null;
        currentState = null;
        joinCode = null;
    }

    /** After a signaling reconnect, re-announce an ongoing hosting session. */
    public static synchronized void reannounce() {
        if (!hosting) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc == null ? null : mc.getSingleplayerServer();
        BurrowConnection connection = BurrowClient.connection();
        if (server == null) {
            hosting = false;
            joinCode = null;
            closeTunnels();
            closeLocalServer();
            return;
        }
        joinCode = null;
        HostWorldState state = worldState(server);
        if (connection != null && state != null) {
            sendSession(connection, server, state, true, Math.max(1, server.getPlayerCount()), null);
        }
        notifyListeners();
    }

    /** The Burrow server told us a player is joining — record join metadata and open a relay tunnel. */
    public static void onRelayHost(
            String roomId,
            String token,
            String relayPath,
            @Nullable UUID joinerUuid,
            @Nullable String joinSource,
            @Nullable String viaName
    ) {
        if (!hosting || localEndpoint == null) {
            return;
        }
        HostWorldState state = worldState();
        if (state != null && joinerUuid != null) {
            if (state.isBanned(joinerUuid) || state.locked()) {
                return;
            }
            if (joinSource != null) {
                state.recordJoinInfo(joinerUuid, joinSource, viaName);
            }
        }
        String url = relayUrl(relayPath, roomId, token);
        Thread worker = new Thread(() -> {
            RelayTunnel tunnel = new RelayTunnel(RelayTunnel.Mode.HOST, url, localEndpoint, () -> {
                TUNNELS.remove(roomId);
                if (joinerUuid != null) {
                    TUNNELS_BY_PLAYER.remove(joinerUuid);
                }
            });
            TUNNELS.put(roomId, tunnel);
            if (joinerUuid != null) {
                TUNNELS_BY_PLAYER.put(joinerUuid, tunnel);
            }
            try {
                tunnel.connectBlocking(10);
            } catch (Throwable t) {
                tunnel.close();
                TUNNELS.remove(roomId);
                if (joinerUuid != null) {
                    TUNNELS_BY_PLAYER.remove(joinerUuid, tunnel);
                }
            }
        }, "burrow-host-relay");
        worker.setDaemon(true);
        worker.start();
    }

    public static void closeTunnelForPlayer(UUID uuid) {
        if (uuid == null) return;
        RelayTunnel tunnel = TUNNELS_BY_PLAYER.remove(uuid);
        if (tunnel != null) {
            tunnel.close();
        }
        VoiceBridge.closeHostTunnelForPlayer(uuid);
    }

    private static List<UUID> collectOnlinePlayerUuids(IntegratedServer server, @Nullable UUID excludingPlayer) {
        List<UUID> out = new ArrayList<>();
        try {
            for (var p : server.getPlayerList().getPlayers()) {
                if (p == null) continue;
                UUID u = p.getUUID();
                if (u != null && !u.equals(excludingPlayer)) {
                    out.add(u);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void sendSession(
            BurrowConnection connection,
            IntegratedServer server,
            HostWorldState state,
            boolean open,
            int playerCount,
            @Nullable UUID excludingPlayer
    ) {
        String worldName = safeWorldName(server);
        String gameVersion = BurrowClient.minecraftVersion();
        List<UUID> players = collectOnlinePlayerUuids(server, excludingPlayer);
        if (open) {
            connection.sessionOpen(
                    worldName, gameVersion,
                    state.access().wire(), state.invitePerm().wire(),
                    state.locked(), state.maxPlayers(), playerCount,
                    state.operatorUuids(), state.bannedUuids(), players);
        } else {
            connection.sessionUpdate(
                    worldName, gameVersion,
                    state.access().wire(), state.invitePerm().wire(),
                    state.locked(), state.maxPlayers(), playerCount,
                    state.operatorUuids(), state.bannedUuids(), players);
        }
        ActivityTracker.refreshDiscord();
    }

    private static void closeTunnels() {
        for (RelayTunnel tunnel : TUNNELS.values()) {
            tunnel.close();
        }
        TUNNELS.clear();
        TUNNELS_BY_PLAYER.clear();
        VoiceBridge.closeAllHostTunnels();
    }

    private static void closeLocalServer() {
        Channel server = localServer;
        localServer = null;
        localEndpoint = null;
        if (server != null) {
            server.close();
        }
    }

    public static String relayUrl(String relayPath, String roomId, String token) {
        return BurrowClient.relayBase() + relayPath + "?room=" + roomId + "&token=" + token;
    }

    public static String safeWorldName(IntegratedServer server) {
        try {
            String name = server.getWorldData().getLevelName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (Throwable ignored) {
        }
        return "a world";
    }
}
