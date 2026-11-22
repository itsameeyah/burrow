package dev.meeyah.burrow.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Burrow client configuration, stored as JSON in the config folder.
 */
public class BurrowConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("burrow.json");
    public static final int MAX_RECENT_PLAYERS = 50;

    public static final class RecentPlayer {
        public String uuid;
        public String name;
        public String source;
        public long lastSeenMs;

        public RecentPlayer() {}

        public RecentPlayer(String uuid, String name, String source, long lastSeenMs) {
            this.uuid = uuid;
            this.name = name;
            this.source = source;
            this.lastSeenMs = lastSeenMs;
        }

        public @Nullable UUID parsedUuid() {
            if (uuid == null || uuid.isBlank()) return null;
            try {
                return UUID.fromString(uuid);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** WebSocket URL of the Burrow signaling server. */
    public String serverUrl = "wss://burrow.meeyah.dev";

    /** Connect to the signaling server automatically when the game starts. */
    public boolean connectAutomatically = true;

    /** Do Not Disturb: shows "Do Not Disturb" to friends and mutes/hides incoming toast notifications. */
    public boolean doNotDisturb = false;

    /** Publish current world / server status to local Discord Rich Presence. */
    public boolean discordRichPresence = true;

    /** Favorite friend UUIDs (lowercase strings), pinned to the top of friend/player/world lists. */
    public List<String> favoriteFriends = new ArrayList<>();

    /** Most recent 50 players encountered across Burrow worlds and external Minecraft servers. */
    public List<RecentPlayer> recentPlayers = new ArrayList<>();

    public static BurrowConfig load() {
        BurrowConfig cfg = null;
        if (Files.exists(PATH)) {
            try {
                String raw = Files.readString(PATH);
                cfg = GSON.fromJson(raw, BurrowConfig.class);
                // Migrate legacy "incognito" boolean to "doNotDisturb" if present.
                if (cfg != null) {
                    JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
                    if (!obj.has("doNotDisturb") && obj.has("incognito") && obj.get("incognito").isJsonPrimitive()) {
                        cfg.doNotDisturb = obj.get("incognito").getAsBoolean();
                    }
                }
            } catch (Exception e) {
                cfg = null;
            }
        }
        if (cfg == null) {
            cfg = new BurrowConfig();
        }
        if (cfg.favoriteFriends == null) {
            cfg.favoriteFriends = new ArrayList<>();
        }
        if (cfg.recentPlayers == null) {
            cfg.recentPlayers = new ArrayList<>();
        } else if (cfg.recentPlayers.size() > MAX_RECENT_PLAYERS) {
            cfg.recentPlayers = new ArrayList<>(cfg.recentPlayers.subList(0, MAX_RECENT_PLAYERS));
        }
        cfg.save();
        return cfg;
    }

    public synchronized boolean isFavorite(@Nullable UUID uuid) {
        if (uuid == null || favoriteFriends == null) return false;
        return favoriteFriends.contains(uuid.toString().toLowerCase(Locale.ROOT));
    }

    public synchronized boolean toggleFavorite(@Nullable UUID uuid) {
        if (uuid == null) return false;
        if (favoriteFriends == null) favoriteFriends = new ArrayList<>();
        String key = uuid.toString().toLowerCase(Locale.ROOT);
        boolean nowFav;
        if (favoriteFriends.contains(key)) {
            favoriteFriends.remove(key);
            nowFav = false;
        } else {
            favoriteFriends.add(key);
            nowFav = true;
        }
        save();
        return nowFav;
    }

    public synchronized List<RecentPlayer> recentPlayersSnapshot() {
        if (recentPlayers == null) return List.of();
        return new ArrayList<>(recentPlayers);
    }

    /**
     * Batch-record recently seen players. Returns true if the list changed.
     */
    public synchronized boolean recordRecentPlayers(List<RecentPlayer> seen) {
        if (seen == null || seen.isEmpty()) return false;
        if (recentPlayers == null) recentPlayers = new ArrayList<>();
        boolean changed = false;
        for (RecentPlayer entry : seen) {
            if (entry == null || entry.uuid == null || entry.uuid.isBlank()
                    || entry.name == null || entry.name.isBlank()) {
                continue;
            }
            String key = entry.uuid.toLowerCase(Locale.ROOT);
            int existingIdx = -1;
            for (int i = 0; i < recentPlayers.size(); i++) {
                RecentPlayer cur = recentPlayers.get(i);
                if (cur != null && cur.uuid != null && cur.uuid.equalsIgnoreCase(key)) {
                    existingIdx = i;
                    break;
                }
            }
            if (existingIdx >= 0) {
                RecentPlayer existing = recentPlayers.get(existingIdx);
                // Avoid rewriting disk constantly for the same top player within 60s.
                if (existingIdx == 0
                        && entry.name.equals(existing.name)
                        && (entry.source == null ? "" : entry.source).equals(existing.source == null ? "" : existing.source)
                        && (entry.lastSeenMs - existing.lastSeenMs) < 60_000L) {
                    continue;
                }
                recentPlayers.remove(existingIdx);
            }
            recentPlayers.add(0, new RecentPlayer(
                    key,
                    entry.name,
                    entry.source == null ? "" : entry.source,
                    entry.lastSeenMs));
            changed = true;
        }
        while (recentPlayers.size() > MAX_RECENT_PLAYERS) {
            recentPlayers.remove(recentPlayers.size() - 1);
            changed = true;
        }
        if (changed) {
            save();
        }
        return changed;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this));
        } catch (IOException e) {
            // Non-fatal: defaults still work in-memory for this session.
        }
    }
}
