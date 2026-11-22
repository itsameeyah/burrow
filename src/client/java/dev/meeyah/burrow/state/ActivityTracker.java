package dev.meeyah.burrow.state;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.config.BurrowConfig;
import dev.meeyah.burrow.discord.DiscordRpc;
import dev.meeyah.burrow.net.BurrowConnection;
import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.session.HostWorldState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.server.IntegratedServer;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tracks what the local player is currently doing (menus, singleplayer,
 * Burrow friend world, saved server, Direct Connect, or local/LAN server),
 * publishes privacy-respecting presence updates to Burrow and Discord RPC,
 * and records recently met players (up to 50) across worlds and servers.
 *
 * Rules:
 * - Do Not Disturb ON: reports "Do Not Disturb" to Burrow friends, clears
 *   Discord Rich Presence, and mutes/hides incoming toast notifications.
 * - Saved Server List entry (non-local): reports custom server name + IP.
 * - Direct Connect (not in saved server list): reports "Playing Multiplayer"
 *   with no IP exposed.
 * - LAN / localhost / RFC1918 private IP: reports "Playing on local server".
 */
public final class ActivityTracker {
    private static volatile boolean registered = false;
    private static volatile String lastSessionKey = "";
    private static volatile long sessionStartEpoch = DiscordRpc.nowEpochSeconds();
    private static int recentCaptureTicks = 0;

    private ActivityTracker() {}

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(() -> {
                    pushCurrent();
                    captureRecentPlayers(client);
                }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                client.execute(() -> {
                    captureRecentPlayers(client);
                    pushIdle();
                }));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++recentCaptureTicks >= 80) {
                recentCaptureTicks = 0;
                captureRecentPlayers(client);
            }
        });
    }

    /** Scan the current world/server tab list and persist real players into Recent 50. */
    public static void captureRecentPlayers(Minecraft mc) {
        if (mc == null) return;
        BurrowConfig cfg = BurrowClient.config();
        if (cfg == null) return;
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return;
        UUID selfId = mc.player != null ? mc.player.getUUID() : BurrowClient.friends().selfUuid();
        String source = describeCurrentLocation(mc);
        long now = System.currentTimeMillis();

        List<BurrowConfig.RecentPlayer> batch = new ArrayList<>();
        try {
            for (PlayerInfo info : conn.getOnlinePlayers()) {
                if (info == null || info.getProfile() == null) continue;
                UUID id = info.getProfile().id();
                String name = info.getProfile().name();
                if (id == null || name == null || id.equals(selfId)) continue;
                // Skip fake server tab-list NPCs (version 2 UUIDs or invalid MC usernames).
                int ver = id.version();
                if (ver != 4 && ver != 3) continue;
                if (!isValidMinecraftName(name)) continue;
                batch.add(new BurrowConfig.RecentPlayer(id.toString(), name, source, now));
            }
        } catch (Throwable ignored) {
            return;
        }
        if (!batch.isEmpty()) {
            cfg.recordRecentPlayers(batch);
        }
    }

    private static boolean isValidMinecraftName(String name) {
        int len = name.length();
        if (len < 3 || len > 16) return false;
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    private static String describeCurrentLocation(Minecraft mc) {
        IntegratedServer sp = mc.getSingleplayerServer();
        if (sp != null) {
            return HostSession.safeWorldName(sp);
        }
        ServerData current = mc.getCurrentServer();
        if (current == null) {
            return "";
        }
        String ip = current.ip == null ? "" : current.ip.trim();
        if (ip.endsWith(".burrow") || ip.contains(".burrow:")) {
            return (current.name != null && !current.name.isBlank()) ? current.name.trim() : "Burrow World";
        }
        if (current.isLan() || isLocalOrPrivateHost(ip)) {
            return "Local Server";
        }
        if (current.name != null && !current.name.isBlank()) {
            return current.name.trim();
        }
        return ip.isBlank() ? "Multiplayer" : ip;
    }

    public static void pushIdle() {
        BurrowConnection conn = BurrowClient.connection();
        BurrowConfig cfg = BurrowClient.config();
        if (cfg != null && cfg.doNotDisturb) {
            if (conn != null) {
                conn.updatePresence("dnd", "", "", "");
            }
            DiscordRpc.clear();
            return;
        }
        if (conn != null) {
            conn.updatePresence("idle", "", "", "");
        }
        updateDiscord("idle", "In Menus", "Minecraft " + BurrowClient.minecraftVersion(), 0, 0);
    }

    public static void pushCurrent() {
        syncState(true);
    }

    /** Re-sync Discord Rich Presence without re-sending Burrow signaling presence. */
    public static void refreshDiscord() {
        syncState(false);
    }

    private static void syncState(boolean sendBurrowPresence) {
        Minecraft mc = Minecraft.getInstance();
        BurrowConnection conn = sendBurrowPresence ? BurrowClient.connection() : null;
        BurrowConfig cfg = BurrowClient.config();
        if (mc == null || cfg == null) {
            return;
        }

        if (cfg.doNotDisturb) {
            if (conn != null) {
                conn.updatePresence("dnd", "", "", "");
            }
            DiscordRpc.clear();
            return;
        }

        IntegratedServer sp = mc.getSingleplayerServer();
        boolean inSingleplayer = sp != null;
        ServerData current = mc.getCurrentServer();

        if (!inSingleplayer && current == null) {
            if (conn != null) {
                conn.updatePresence("idle", "", "", "");
            }
            updateDiscord("idle", "In Menus", "Minecraft " + BurrowClient.minecraftVersion(), 0, 0);
            return;
        }

        if (inSingleplayer) {
            if (conn != null) {
                conn.updatePresence("singleplayer", "", "", "");
            }
            if (HostSession.isHosting()) {
                HostWorldState ws = HostSession.worldState(sp);
                String worldName = HostSession.safeWorldName(sp);
                String accessLabel = ws != null ? ws.access().label() : "Friends Only";
                String state = (ws != null && ws.locked()) ? accessLabel + " · Locked" : accessLabel;
                int players = Math.max(1, sp.getPlayerCount());
                int max = ws != null ? ws.maxPlayers() : 8;
                updateDiscord("host:" + worldName, "Hosting " + worldName, state, players, max);
            } else {
                updateDiscord("sp:" + HostSession.safeWorldName(sp),
                        "Playing Singleplayer", "Minecraft " + BurrowClient.minecraftVersion(), 0, 0);
            }
            return;
        }

        String ip = current.ip == null ? "" : current.ip.trim();
        if (ip.endsWith(".burrow") || ip.contains(".burrow:")) {
            String label = current.name == null ? "" : current.name.trim();
            if (conn != null) {
                conn.updatePresence("burrow", "", "", label);
            }
            String details = label.isEmpty() ? "Playing with Friends" : "Playing in " + label;
            updateDiscord("burrow:" + ip, details, "via Burrow", 0, 0);
            return;
        }

        if (current.isLan() || isLocalOrPrivateHost(ip)) {
            if (conn != null) {
                conn.updatePresence("local", "", "", "");
            }
            updateDiscord("local", "Playing on a local server", "Minecraft " + BurrowClient.minecraftVersion(), 0, 0);
            return;
        }

        // Only expose server name + IP when it is saved in the player's server list.
        ServerData saved = findInSavedServers(mc, ip);
        if (saved != null) {
            String displayName = (saved.name != null && !saved.name.isBlank()) ? saved.name.trim() : ip;
            if (conn != null) {
                conn.updatePresence("server", displayName, ip, "");
            }
            updateDiscord("server:" + ip, "Playing on " + displayName, ip, 0, 0);
            return;
        }

        // Direct Connect: hide the IP.
        if (conn != null) {
            conn.updatePresence("multiplayer", "", "", "");
        }
        updateDiscord("mp", "Playing Multiplayer", "Minecraft " + BurrowClient.minecraftVersion(), 0, 0);
    }

    private static void updateDiscord(String sessionKey, String details, String state, int partySize, int partyMax) {
        BurrowConfig cfg = BurrowClient.config();
        if (cfg != null && (cfg.doNotDisturb || !cfg.discordRichPresence)) {
            DiscordRpc.clear();
            return;
        }
        if (!sessionKey.equals(lastSessionKey)) {
            lastSessionKey = sessionKey;
            sessionStartEpoch = DiscordRpc.nowEpochSeconds();
        }
        DiscordRpc.setPresence(new DiscordRpc.Presence(
                details,
                state,
                sessionStartEpoch,
                partySize,
                partyMax,
                "Minecraft " + BurrowClient.minecraftVersion() + " · Burrow"
        ));
    }

    private static ServerData findInSavedServers(Minecraft mc, String ip) {
        try {
            ServerList list = new ServerList(mc);
            list.load();
            ServerData exact = list.get(ip);
            if (exact != null) {
                return exact;
            }
            String normalized = normalizeIp(ip);
            for (int i = 0; i < list.size(); i++) {
                ServerData entry = list.get(i);
                if (entry != null && entry.ip != null && normalizeIp(entry.ip).equals(normalized)) {
                    return entry;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String normalizeIp(String raw) {
        String s = raw.trim().toLowerCase();
        if (s.endsWith(":25565")) {
            s = s.substring(0, s.length() - 6);
        }
        return s;
    }

    private static boolean isLocalOrPrivateHost(String ipPort) {
        if (ipPort == null || ipPort.isBlank()) {
            return true;
        }
        String host = ipPort.trim().toLowerCase();
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end > 0) host = host.substring(1, end);
        } else {
            int colon = host.lastIndexOf(':');
            if (colon > 0 && host.indexOf(':') == colon) {
                host = host.substring(0, colon);
            }
        }
        if (host.equals("localhost") || host.equals("::1") || host.equals("0.0.0.0") || host.endsWith(".local")) {
            return true;
        }
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) {
            return true;
        }
        if (host.startsWith("172.")) {
            String[] parts = host.split("\\.");
            if (parts.length == 4) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) return true;
                } catch (NumberFormatException ignored) {}
            }
        }
        try {
            // Only parse literal IPs without triggering DNS lookups.
            if (host.matches("[0-9.]+") || host.contains(":")) {
                InetAddress addr = InetAddress.getByName(host);
                return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress();
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
