package dev.meeyah.burrow.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Burrow client configuration, stored as JSON in the config folder.
 *
 * The signaling server URL is a plain setting so the mod can be pointed at a
 * LAN address during development and at the public tunnel hostname later
 * without any code changes.
 */
public class BurrowConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("burrow.json");

    /** WebSocket URL of the Burrow signaling server. */
    public String serverUrl = "ws://192.168.0.126:8787";

    /** Connect to the signaling server automatically when the game starts. */
    public boolean connectAutomatically = true;

    /** Hide world/server details from friends and show only "Playing Multiplayer". */
    public boolean incognito = false;

    public static BurrowConfig load() {
        BurrowConfig cfg = null;
        if (Files.exists(PATH)) {
            try {
                cfg = GSON.fromJson(Files.readString(PATH), BurrowConfig.class);
            } catch (Exception e) {
                cfg = null;
            }
        }
        if (cfg == null) {
            cfg = new BurrowConfig();
        }
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this));
        } catch (IOException e) {
            // Non-fatal: defaults still work in-memory for this session.
        }
    }
}
