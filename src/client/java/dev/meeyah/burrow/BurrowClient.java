package dev.meeyah.burrow;

import dev.meeyah.burrow.config.BurrowConfig;
import dev.meeyah.burrow.net.BurrowConnection;
import dev.meeyah.burrow.state.FriendsState;
import net.fabricmc.api.ClientModInitializer;

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
}
