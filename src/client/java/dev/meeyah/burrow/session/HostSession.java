package dev.meeyah.burrow.session;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.net.BurrowConnection;
import dev.meeyah.burrow.net.RelayTunnel;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.jetbrains.annotations.Nullable;

import java.net.SocketAddress;

/**
 * Host side of Burrow: shares the current singleplayer world with friends.
 *
 * Flow: publish the world to the local scope (this is what makes vanilla
 * bind its server channel, and what lets our mixin capture the game's own
 * channel initializer), then attach a private in-process endpoint wired to
 * that exact initializer, announce the session to the Burrow server, and
 * when a friend joins, bridge the relay websocket to the private endpoint.
 */
public final class HostSession {
    private static volatile @Nullable ChannelHandler capturedInitializer;
    private static volatile @Nullable SocketAddress localEndpoint;
    private static volatile @Nullable RelayTunnel relay;
    private static volatile boolean hosting;

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
        if (!server.isPublished()) {
            server.publishServer(MinecraftServer.MultiplayerScope.LAN, null, false, 0);
        }
        ChannelHandler initializer = capturedInitializer;
        if (initializer == null) {
            return; // could not capture; not fatal, just not shareable
        }

        // A private in-process endpoint using the captured vanilla pipeline:
        // tunnels reaching it are indistinguishable from real LAN sockets.
        ChannelFuture bound = new ServerBootstrap()
                .group(EventLoopGroupHolder.local().eventLoopGroup())
                .channel(LocalServerChannel.class)
                .childHandler(initializer)
                .localAddress(new LocalAddress("burrow-host"))
                .bind()
                .syncUninterruptibly();
        localEndpoint = bound.channel().localAddress();

        String worldName = safeWorldName(server);
        String gameVersion = BurrowClient.minecraftVersion();
        hosting = true;
        connection.sessionOpen(worldName, gameVersion, 10);
    }

    public static synchronized void stop() {
        if (!hosting) {
            return;
        }
        hosting = false;
        RelayTunnel tunnel = relay;
        relay = null;
        if (tunnel != null) {
            tunnel.close();
        }
        localEndpoint = null;
        BurrowConnection connection = BurrowClient.connection();
        if (connection != null) {
            connection.sessionClose();
        }
    }

    /** The Burrow server told us a friend is joining — open the relay tunnel as host. */
    public static void onRelayHost(String roomId, String token, String relayPath) {
        SocketAddress endpoint = localEndpoint;
        if (!hosting || endpoint == null) {
            return;
        }
        String url = relayUrl(relayPath, roomId, token);
        Thread worker = new Thread(() -> {
            RelayTunnel tunnel = new RelayTunnel(RelayTunnel.Mode.HOST, url, endpoint, HostSession::relayClosed);
            try {
                tunnel.connectBlocking(10);
                relay = tunnel;
            } catch (InterruptedException e) {
                tunnel.close();
            }
        }, "burrow-host-relay");
        worker.setDaemon(true);
        worker.start();
    }

    private static void relayClosed() {
        // The friend's tunnel ended; hosting continues so they can rejoin.
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
