package dev.meeyah.burrow.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-world hosting settings, roles, bans, and live join-source metadata.
 *
 * Persisted to {@code <world>/burrow-host.json} so role assignments, player
 * cap, access mode, and bans survive world reloads. Runtime-only fields
 * (locked state, join sources) reset cleanly per session.
 */
public final class HostWorldState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILENAME = "burrow-host.json";

    public enum AccessMode {
        INVITE_ONLY("invite_only", "Invite Only"),
        FRIENDS("friends", "Friends Only"),
        FRIENDS_OF_FRIENDS("friends_of_friends", "Friends of Friends"),
        OPEN("open", "Friend Network");

        private final String wire;
        private final String label;

        AccessMode(String wire, String label) {
            this.wire = wire;
            this.label = label;
        }

        public String wire() { return wire; }
        public String label() { return label; }

        public static AccessMode fromWire(@Nullable String s) {
            if (s == null) return FRIENDS;
            for (AccessMode m : values()) {
                if (m.wire.equals(s)) return m;
            }
            return FRIENDS;
        }
    }

    public enum InvitePerm {
        HOST_ONLY("host_only", "Host Only"),
        OPERATORS("operators", "Operators"),
        ANYONE("anyone", "Anyone");

        private final String wire;
        private final String label;

        InvitePerm(String wire, String label) {
            this.wire = wire;
            this.label = label;
        }

        public String wire() { return wire; }
        public String label() { return label; }

        public static InvitePerm fromWire(@Nullable String s) {
            if (s == null) return HOST_ONLY;
            for (InvitePerm p : values()) {
                if (p.wire.equals(s)) return p;
            }
            return HOST_ONLY;
        }
    }

    public enum Role {
        VISITOR("visitor", "Visitor"),
        MEMBER("member", "Member"),
        OPERATOR("operator", "Operator");

        private final String wire;
        private final String label;

        Role(String wire, String label) {
            this.wire = wire;
            this.label = label;
        }

        public String wire() { return wire; }
        public String label() { return label; }

        public static Role fromWire(@Nullable String s) {
            if (s == null) return MEMBER;
            for (Role r : values()) {
                if (r.wire.equals(s)) return r;
            }
            return MEMBER;
        }
    }

    public record JoinInfo(String source, @Nullable String viaName) {
        public String describe() {
            if ("friend".equals(source)) {
                return "Joined via Friends list";
            }
            if ("friend_of_friend".equals(source)) {
                return viaName != null && !viaName.isBlank()
                        ? "Joined via friend: " + viaName
                        : "Joined via Friends of Friends";
            }
            if ("invite".equals(source)) {
                return viaName != null && !viaName.isBlank()
                        ? "Invited by " + viaName
                        : "Joined via Invite";
            }
            if ("open".equals(source)) {
                return "Joined via Friend Network";
            }
            return "Local LAN";
        }

        public String shortLabel() {
            if ("friend".equals(source)) return "Friend";
            if ("friend_of_friend".equals(source)) {
                return viaName != null && !viaName.isBlank() ? "via " + viaName : "FoF";
            }
            if ("invite".equals(source)) {
                return viaName != null && !viaName.isBlank() ? "Invited by " + viaName : "Invited";
            }
            if ("open".equals(source)) return "Network";
            return "LAN";
        }
    }

    private final Path file;
    private volatile AccessMode access = AccessMode.FRIENDS;
    private volatile InvitePerm invitePerm = InvitePerm.HOST_ONLY;
    private volatile Role defaultRole = Role.MEMBER;
    private volatile int maxPlayers = 8;
    private volatile boolean locked = false;
    private final Map<UUID, Role> roles = new ConcurrentHashMap<>();
    private final Map<UUID, String> banned = new ConcurrentHashMap<>();
    private final Map<UUID, JoinInfo> joinInfos = new ConcurrentHashMap<>();

    private HostWorldState(Path file) {
        this.file = file;
    }

    public static HostWorldState load(MinecraftServer server) {
        Path path = server.getWorldPath(LevelResource.ROOT).resolve(FILENAME);
        HostWorldState state = new HostWorldState(path);
        if (Files.exists(path)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (o.has("access")) state.access = AccessMode.fromWire(o.get("access").getAsString());
                if (o.has("invitePerm")) state.invitePerm = InvitePerm.fromWire(o.get("invitePerm").getAsString());
                if (o.has("defaultRole")) state.defaultRole = Role.fromWire(o.get("defaultRole").getAsString());
                if (o.has("maxPlayers")) state.maxPlayers = Math.clamp(o.get("maxPlayers").getAsInt(), 2, 32);
                if (o.has("roles") && o.get("roles").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("roles").entrySet()) {
                        try {
                            state.roles.put(UUID.fromString(e.getKey()), Role.fromWire(e.getValue().getAsString()));
                        } catch (Exception ignored) {}
                    }
                }
                if (o.has("banned") && o.get("banned").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("banned").entrySet()) {
                        try {
                            state.banned.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {
                // Fall back to defaults on corrupt file.
            }
        }
        return state;
    }

    public synchronized void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("access", this.access.wire());
            o.addProperty("invitePerm", this.invitePerm.wire());
            o.addProperty("defaultRole", this.defaultRole.wire());
            o.addProperty("maxPlayers", this.maxPlayers);

            JsonObject rolesObj = new JsonObject();
            for (Map.Entry<UUID, Role> e : this.roles.entrySet()) {
                rolesObj.addProperty(e.getKey().toString(), e.getValue().wire());
            }
            o.add("roles", rolesObj);

            JsonObject bannedObj = new JsonObject();
            for (Map.Entry<UUID, String> e : this.banned.entrySet()) {
                bannedObj.addProperty(e.getKey().toString(), e.getValue());
            }
            o.add("banned", bannedObj);

            Files.createDirectories(this.file.getParent());
            Files.writeString(this.file, GSON.toJson(o));
        } catch (Exception ignored) {
        }
    }

    public AccessMode access() { return access; }
    public void setAccess(AccessMode access) { this.access = access; save(); }

    public InvitePerm invitePerm() { return invitePerm; }
    public void setInvitePerm(InvitePerm invitePerm) { this.invitePerm = invitePerm; save(); }

    public Role defaultRole() { return defaultRole; }
    public void setDefaultRole(Role defaultRole) { this.defaultRole = defaultRole; save(); }

    public int maxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = Math.clamp(maxPlayers, 2, 32); save(); }

    public boolean locked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    public Role roleOf(UUID uuid) {
        return roles.getOrDefault(uuid, defaultRole);
    }

    public void setRole(UUID uuid, Role role) {
        roles.put(uuid, role);
        save();
    }

    public List<UUID> operatorUuids() {
        List<UUID> ops = new ArrayList<>();
        for (Map.Entry<UUID, Role> e : roles.entrySet()) {
            if (e.getValue() == Role.OPERATOR) {
                ops.add(e.getKey());
            }
        }
        return ops;
    }

    public boolean isBanned(UUID uuid) {
        return banned.containsKey(uuid);
    }

    public void ban(UUID uuid, String name) {
        banned.put(uuid, name == null || name.isBlank() ? uuid.toString() : name);
        save();
    }

    public void unban(UUID uuid) {
        banned.remove(uuid);
        save();
    }

    public Map<UUID, String> bannedSnapshot() {
        return new LinkedHashMap<>(banned);
    }

    public List<UUID> bannedUuids() {
        return List.copyOf(banned.keySet());
    }

    public void recordJoinInfo(UUID uuid, String source, @Nullable String viaName) {
        if (uuid != null && source != null && !source.isBlank()) {
            joinInfos.put(uuid, new JoinInfo(source, viaName));
        }
    }

    public JoinInfo joinInfoOf(UUID uuid) {
        return joinInfos.getOrDefault(uuid, new JoinInfo("local", null));
    }

    public void clearJoinInfo(UUID uuid) {
        joinInfos.remove(uuid);
    }
}
