package dev.meeyah.burrow.session;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.net.BurrowConnection;
import dev.meeyah.burrow.net.RelayTunnel;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Host side of Burrow: shares the current singleplayer world with friends.
 *
 * Flow: publish the world to the LAN scope (this makes vanilla bind its
 * server channel, letting our mixin capture the game's own channel
 * initializer), attach a private in-process endpoint wired to that exact
 * initializer, announce the session, and when friends join, bridge each
 * relay websocket to the private endpoint — one tunnel per joiner.
 */
public final class HostSession {
    /** Local address id of the private host endpoint; the auth mixin matches on it. */
    public static final String HOST_ADDRESS_ID = "burrow-host";

    private static volatile @Nullable ChannelHandler capturedInitializer;
    private static volatile @Nullable Channel localServer;
    private static volatile @Nullable SocketAddress localEndpoint;
    private static volatile boolean wePublished;
    private static volatile boolean hosting;
    /** One tunnel per relay room (one per joining friend). */
    private static final Map<String, RelayTunnel> TUNNELS = new ConcurrentHashMap<>();

    private HostSession() {}

    /** Called by our ServerConnectionListener mixin whenever vanilla binds a server channel. */
    public static void rememberInitializer(ChannelHandler initializer) {
        capturedInitializer = initializer;
    }

    public static boolean isHosting() {
        return hosting;
    }

    public static synchronized void start() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        BurrowConnection connection = BurrowClient.connection();
        if (server == null || hosting || connection == null) {
            return;
        }

        // Publish to the LAN scope so the game creates its real server
        // channel — and our mixin captures the initializer during the bind.
        // Remember whether WE published, so stopping only unpublishes ours.
        if (!server.isPublished()) {
            server.publishServer(MinecraftServer.MultiplayerScope.LAN, null, false, 0);
            wePublished = true;
        }
        ChannelHandler initializer = capturedInitializer;
        if (initializer == null) {
            return; // could not capture; not fatal, just not shareable
        }

        // A private in-process endpoint using the captured vanilla pipeline:
        // tunnels reaching it are indistinguishable from real LAN sockets.
        // The Connection auth mixin marks exactly these connections as
        // remote, so friends get full Mojang authentication + encryption.
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

        String worldName = safeWorldName(server);
        String gameVersion = BurrowClient.minecraftVersion();
        hosting = true;
        connection.sessionOpen(worldName, gameVersion, 10);
    }

    public static synchronized void stop() {
        if (!hosting) {
            // Still clean up any straggler state.
            closeTunnels();
            closeLocalServer();
            return;
        }
        hosting = false;
        closeTunnels();
        closeLocalServer();

        BurrowConnection connection = BurrowClient.connection();
        if (connection != null) {
            connection.sessionClose();
        }
    }

    /** Belt-and-braces cleanup when the singleplayer world itself goes away. */
    public static void ensureStopped() {
        if (hosting) {
            stop();
        } else {
            closeTunnels();
            closeLocalServer();
        }
    }

    /** After a signaling reconnect, re-announce an ongoing hosting session. */
    public static void reannounce() {
        if (!hosting) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        BurrowConnection connection = BurrowClient.connection();
        if (server == null) {
            hosting = false;
            closeTunnels();
            closeLocalServer();
            return;
        }
        if (connection != null) {
            connection.sessionOpen(safeWorldName(server), BurrowClient.minecraftVersion(), 10);
        }
    }

    /** The Burrow server told us a friend is joining — open one relay tunnel as host. */
    public static void onRelayHost(String roomId, String token, String relayPath) {
        if (!hosting || localEndpoint == null) {
            return;
        }
        String url = relayUrl(relayPath, roomId, token);
        Thread worker = new Thread(() -> {
            RelayTunnel tunnel = new RelayTunnel(RelayTunnel.Mode.HOST, url, localEndpoint,
                    () -> TUNNELS.remove(roomId));
            TUNNELS.put(roomId, tunnel); // register before connecting so stop() can reach it
            try {
                tunnel.connectBlocking(10);
            } catch (InterruptedException e) {
                tunnel.close();
                TUNNELS.remove(roomId);
            }
        }, "burrow-host-relay");
        worker.setDaemon(true);
        worker.start();
    }

    private static void closeTunnels() {
        for (RelayTunnel tunnel : TUNNELS.values()) {
            tunnel.close();
        }
        TUNNELS.clear();
    }

    private static void closeLocalServer() {
        Channel server = localServer;
        localServer = null;
        localEndpoint = null;
        if (server != null) {
            server.close();
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer integrated = mc == null ? null : mc.getSingleplayerServer();
        if (wePublished && integrated != null && integrated.isPublished()) {
            wePublished = false;
            integrated.unpublishServer();
        }
    }

    static String relayUrl(String relayPath, String roomId, String token) {
        return BurrowClient.relayBase() + relayPath + "?room=" + roomId + "&token=" + token;
    }

    private static String safeWorldName(IntegratedServer server) {
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
