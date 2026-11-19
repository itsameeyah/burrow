package dev.meeyah.burrow;

import dev.meeyah.burrow.config.BurrowConfig;
import dev.meeyah.burrow.net.BurrowConnection;
import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.state.FriendsState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Burrow — your own friends list and world sharing, self-hosted.
 *
 * Replaces the vanilla friends list with one backed by your own signaling
 * server, and lets friends join your singleplayer world through a tunnel
 * without either side ever touching vanilla networking internals.
 */
public class BurrowClient implements ClientModInitializer {
    public static final String MOD_ID = "burrow";

    private static BurrowConfig config;
    private static FriendsState friends;
    private static BurrowConnection connection;

    @Override
    public void onInitializeClient() {
        config = BurrowConfig.load();
        friends = new FriendsState();
        connection = new BurrowConnection(config, friends);

        // Tear down hosting + stale server channel initializer whenever the
        // integrated server shuts down (world exit, quit to title, world switch).
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> HostSession.onServerStopping());

        if (config.connectAutomatically) {
            connection.connectAsync();
        }
    }

    public static BurrowConfig config() {
        return config;
    }

    public static FriendsState friends() {
        return friends;
    }

    public static BurrowConnection connection() {
        return connection;
    }

    /** Minecraft version string reported to the Burrow server on session open. */
    public static String minecraftVersion() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    /** Signaling URL: config base with exactly one /ws path. */
    public static String signalingUrl() {
        return relayBase() + "/ws";
    }

    /** Origin base of the Burrow server (no trailing slash, no /ws path), used to derive relay URLs. */
    public static String relayBase() {
        String url = config == null ? "" : config.serverUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/ws")) {
            url = url.substring(0, url.length() - 3);
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
        }
        return url;
    }
}
