package dev.meeyah.burrow.state;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.ui.BurrowToasts;
import net.minecraft.client.Minecraft;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Client-side model of friends (including rich server/world presence),
 * incoming/outgoing friend requests, discoverable non-friend worlds
 * (friends-of-friends / open), world invites, and incoming invite requests.
 */
public final class FriendsState {
    public record Friend(
            UUID uuid,
            String name,
            String presence,
            boolean hosting,
            String worldName,
            String access,
            boolean locked,
            int playerCount,
            int maxPlayers,
            String activity,
            String serverName,
            String serverIp,
            String activityLabel,
            String gameVersion
    ) {
        public Friend(
                UUID uuid,
                String name,
                String presence,
                boolean hosting,
                String worldName,
                String access,
                boolean locked,
                int playerCount,
                int maxPlayers,
                String activity,
                String serverName,
                String serverIp,
                String activityLabel
        ) {
            this(uuid, name, presence, hosting, worldName, access, locked, playerCount, maxPlayers,
                    activity, serverName, serverIp, activityLabel, "");
        }

        public boolean isOnline() {
            return "online".equalsIgnoreCase(presence);
        }

        public boolean isVersionMismatch() {
            return hosting
                    && gameVersion != null
                    && !gameVersion.isBlank()
                    && !gameVersion.equals(BurrowClient.minecraftVersion());
        }

        public boolean hasJoinableServer() {
            return isOnline() && "server".equals(activity) && serverIp != null && !serverIp.isBlank();
        }

        /** True when this friend is actually inside a world or server (not idle on the title screen). */
        public boolean isInGame() {
            if (!isOnline()) return false;
            if (hosting) return true;
            return "singleplayer".equals(activity)
                    || "server".equals(activity)
                    || "local".equals(activity)
                    || "multiplayer".equals(activity)
                    || "burrow".equals(activity);
        }

        public boolean isDnd() {
            return isOnline() && "dnd".equals(activity);
        }

        public String activitySubtitle() {
            if (!isOnline()) {
                return "Offline";
            }
            if (hosting) {
                String world = (worldName == null || worldName.isBlank()) ? "a world" : worldName;
                String count = maxPlayers > 0 ? " (" + playerCount + "/" + maxPlayers + ")" : "";
                return "Hosting " + world + count + (locked ? " · Locked" : "");
            }
            if ("dnd".equals(activity)) {
                return "Do Not Disturb";
            }
            if ("server".equals(activity) && serverIp != null && !serverIp.isBlank()) {
                if (serverName != null && !serverName.isBlank() && !serverName.equalsIgnoreCase(serverIp)) {
                    return "Playing on " + serverName + " (" + serverIp + ")";
                }
                return "Playing on (" + serverIp + ")";
            }
            if ("local".equals(activity)) {
                return "Playing on local server";
            }
            if ("burrow".equals(activity)) {
                return (activityLabel != null && !activityLabel.isBlank())
                        ? "Playing in " + activityLabel
                        : "Playing in a friend's world";
            }
            if ("multiplayer".equals(activity)) {
                return "Playing Multiplayer";
            }
            if ("singleplayer".equals(activity)) {
                return "Playing Singleplayer";
            }
            return "Online";
        }
    }

    public static final long INVITE_TTL_MS = 30_000L;

    public record Request(UUID uuid, String name, boolean outgoing) {}

    public record InviteRequest(UUID from, String fromName, long expiresAtMs) {
        public boolean expired() {
            return System.currentTimeMillis() > expiresAtMs;
        }
    }

    public record DiscoverableWorld(
            UUID host,
            String hostName,
            String worldName,
            String access,
            String source,
            String viaName,
            boolean locked,
            int playerCount,
            int maxPlayers,
            String gameVersion
    ) {
        public DiscoverableWorld(
                UUID host,
                String hostName,
                String worldName,
                String access,
                String source,
                String viaName,
                boolean locked,
                int playerCount,
                int maxPlayers
        ) {
            this(host, hostName, worldName, access, source, viaName, locked, playerCount, maxPlayers, "");
        }

        public boolean isVersionMismatch() {
            return gameVersion != null
                    && !gameVersion.isBlank()
                    && !gameVersion.equals(BurrowClient.minecraftVersion());
        }
    }

    public record WorldInvite(
            UUID host,
            String hostName,
            String worldName,
            String invitedByName,
            long expiresAtMs
    ) {
        public boolean expired() {
            return System.currentTimeMillis() > expiresAtMs;
        }
    }

    public record PresenceUpdate(
            UUID uuid,
            String presence,
            boolean hosting,
            String worldName,
            String activity,
            String serverName,
            String serverIp,
            String activityLabel
    ) {}

    public record SessionUpdate(
            UUID host,
            String hostName,
            boolean active,
            String worldName,
            String access,
            String source,
            String viaName,
            boolean locked,
            int playerCount,
            int maxPlayers,
            String gameVersion
    ) {
        public SessionUpdate(
                UUID host,
                String hostName,
                boolean active,
                String worldName,
                String access,
                String source,
                String viaName,
                boolean locked,
                int playerCount,
                int maxPlayers
        ) {
            this(host, hostName, active, worldName, access, source, viaName, locked, playerCount, maxPlayers, "");
        }
    }

    public record CodePreview(
            String code,
            boolean found,
            @org.jetbrains.annotations.Nullable UUID host,
            String hostName,
            String worldName,
            String gameVersion,
            int playerCount,
            int maxPlayers,
            boolean locked,
            boolean isSelf,
            String error
    ) {
        public boolean isVersionMismatch() {
            return gameVersion != null
                    && !gameVersion.isBlank()
                    && !gameVersion.equals(BurrowClient.minecraftVersion());
        }
    }

    public interface Listener {
        void friendsChanged();
    }

    private final List<Friend> friends = new CopyOnWriteArrayList<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<InviteRequest> inviteRequests = new CopyOnWriteArrayList<>();
    private final List<DiscoverableWorld> discoverable = new CopyOnWriteArrayList<>();
    private final List<WorldInvite> invites = new CopyOnWriteArrayList<>();
    private final Set<UUID> registeredUuids = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> registeredNamesLower = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile UUID selfUuid;
    private volatile String selfName;
    private volatile String lastError;
    private volatile long lastErrorAt;
    private volatile boolean initialSyncDone = false;
    private volatile @org.jetbrains.annotations.Nullable CodePreview codePreview;

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void notifyChanged() {
        fireChanged();
    }

    public @org.jetbrains.annotations.Nullable CodePreview codePreview() {
        return codePreview;
    }

    public void setCodePreview(@org.jetbrains.annotations.Nullable CodePreview preview) {
        this.codePreview = preview;
        fireChanged();
    }

    public void clearCodePreview() {
        if (this.codePreview != null) {
            this.codePreview = null;
            fireChanged();
        }
    }

    public List<Friend> friends() {
        return List.copyOf(friends);
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public List<InviteRequest> inviteRequests() {
        invites.removeIf(WorldInvite::expired);
        inviteRequests.removeIf(InviteRequest::expired);
        return List.copyOf(inviteRequests);
    }

    public List<DiscoverableWorld> discoverableWorlds() {
        return List.copyOf(discoverable);
    }

    public List<WorldInvite> worldInvites() {
        invites.removeIf(WorldInvite::expired);
        return List.copyOf(invites);
    }

    public @org.jetbrains.annotations.Nullable WorldInvite worldInviteFrom(UUID hostUuid) {
        if (hostUuid == null) return null;
        for (WorldInvite inv : invites) {
            if (hostUuid.equals(inv.host()) && !inv.expired()) return inv;
        }
        return null;
    }

    public boolean hasInviteRequestFrom(UUID fromUuid) {
        if (fromUuid == null) return false;
        for (InviteRequest req : inviteRequests) {
            if (fromUuid.equals(req.from()) && !req.expired()) return true;
        }
        return false;
    }

    /** Prune expired 30s invites/requests; returns true if anything was removed. */
    public boolean pruneExpired() {
        boolean removedInv = invites.removeIf(WorldInvite::expired);
        boolean removedReq = inviteRequests.removeIf(InviteRequest::expired);
        if (removedInv || removedReq) {
            fireChanged();
            return true;
        }
        return false;
    }

    public boolean isFriend(UUID uuid) {
        if (uuid == null) return false;
        for (Friend f : friends) {
            if (uuid.equals(f.uuid())) return true;
        }
        return false;
    }

    public boolean isFriendByName(String name) {
        if (name == null || name.isBlank()) return false;
        for (Friend f : friends) {
            if (f.name() != null && f.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public void recordRegisteredUsers(Collection<UUID> uuids, Collection<String> namesLower) {
        registeredUuids.addAll(uuids);
        registeredNamesLower.addAll(namesLower);
        fireChanged();
    }

    public boolean hasBurrowAccount(UUID uuid, String name) {
        if (uuid != null && registeredUuids.contains(uuid)) return true;
        return name != null && registeredNamesLower.contains(name.toLowerCase());
    }

    public UUID selfUuid() {
        return selfUuid;
    }

    public String selfName() {
        return selfName;
    }

    public void setSelf(UUID uuid, String name) {
        this.selfUuid = uuid;
        this.selfName = name;
        fireChanged();
    }

    /** Last server error message, for surfacing in the UI for a few seconds. */
    public void setError(String message) {
        this.lastError = message;
        this.lastErrorAt = System.currentTimeMillis();
        fireChanged();
    }

    /** The most recent error if it is still fresh (5 seconds), else null. */
    public String errorFlash() {
        String err = this.lastError;
        if (err == null || System.currentTimeMillis() - this.lastErrorAt > 5_000) {
            return null;
        }
        return err;
    }

    public void replaceAll(
            List<Friend> newFriends,
            List<Request> incoming,
            List<Request> outgoing,
            List<DiscoverableWorld> newDiscoverable
    ) {
        if (initialSyncDone) {
            Set<UUID> prevIncoming = new HashSet<>();
            for (Request r : requests) {
                if (!r.outgoing() && r.uuid() != null) {
                    prevIncoming.add(r.uuid());
                }
            }
            for (Request r : incoming) {
                if (!r.outgoing() && r.uuid() != null && !prevIncoming.contains(r.uuid())) {
                    BurrowToasts.showFriendRequest(r.uuid(), r.name());
                }
            }
        }
        initialSyncDone = true;

        friends.clear();
        friends.addAll(newFriends);
        requests.clear();
        requests.addAll(incoming);
        requests.addAll(outgoing);
        discoverable.clear();
        discoverable.addAll(newDiscoverable);
        fireChanged();
    }

    public void addInvite(WorldInvite invite) {
        if (invite.host() == null) return;
        invites.removeIf(existing -> invite.host().equals(existing.host()));
        invites.add(invite);
        fireChanged();
    }

    public void dismissInvite(UUID host) {
        if (host == null) return;
        if (invites.removeIf(existing -> host.equals(existing.host()))) {
            fireChanged();
        }
    }

    public void addInviteRequest(InviteRequest req) {
        if (req.from() == null) return;
        inviteRequests.removeIf(existing -> req.from().equals(existing.from()));
        inviteRequests.add(req);
        fireChanged();
    }

    public void dismissInviteRequest(UUID from) {
        if (from == null) return;
        if (inviteRequests.removeIf(existing -> from.equals(existing.from()))) {
            fireChanged();
        }
    }

    public void applyPresence(List<PresenceUpdate> updates) {
        boolean changed = false;
        for (PresenceUpdate u : updates) {
            if (u.uuid() == null) continue;
            for (int i = 0; i < friends.size(); i++) {
                Friend f = friends.get(i);
                if (u.uuid().equals(f.uuid())) {
                    friends.set(i, new Friend(
                            f.uuid(),
                            f.name(),
                            u.presence() != null ? u.presence() : f.presence(),
                            u.hosting(),
                            u.worldName() != null ? u.worldName() : f.worldName(),
                            u.hosting() ? f.access() : "",
                            u.hosting() && f.locked(),
                            u.hosting() ? f.playerCount() : 0,
                            u.hosting() ? f.maxPlayers() : 0,
                            u.activity() != null ? u.activity() : f.activity(),
                            u.serverName() != null ? u.serverName() : f.serverName(),
                            u.serverIp() != null ? u.serverIp() : f.serverIp(),
                            u.activityLabel() != null ? u.activityLabel() : f.activityLabel(),
                            u.hosting() ? f.gameVersion() : ""));
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            fireChanged();
        }
    }

    public void applySessions(List<SessionUpdate> updates) {
        boolean changed = false;
        for (SessionUpdate u : updates) {
            if (u.host() == null || u.host().equals(selfUuid)) continue;
            boolean matchedFriend = false;
            for (int i = 0; i < friends.size(); i++) {
                Friend f = friends.get(i);
                if (u.host().equals(f.uuid())) {
                    friends.set(i, new Friend(
                            f.uuid(),
                            f.name(),
                            u.active() ? "online" : f.presence(),
                            u.active(),
                            u.active() && u.worldName() != null ? u.worldName() : "",
                            u.active() && u.access() != null ? u.access() : "",
                            u.active() && u.locked(),
                            u.active() ? u.playerCount() : 0,
                            u.active() ? u.maxPlayers() : 0,
                            f.activity(),
                            f.serverName(),
                            f.serverIp(),
                            f.activityLabel(),
                            u.active() && u.gameVersion() != null ? u.gameVersion() : ""));
                    matchedFriend = true;
                    changed = true;
                    break;
                }
            }
            if (!u.active()) {
                if (invites.removeIf(inv -> u.host().equals(inv.host()))) {
                    changed = true;
                }
            }
            if (!matchedFriend) {
                boolean removed = discoverable.removeIf(d -> u.host().equals(d.host()));
                if (u.active()) {
                    discoverable.add(new DiscoverableWorld(
                            u.host(),
                            u.hostName() != null ? u.hostName() : "?",
                            u.worldName() != null ? u.worldName() : "a world",
                            u.access() != null ? u.access() : "",
                            u.source() != null ? u.source() : "",
                            u.viaName() != null ? u.viaName() : "",
                            u.locked(),
                            u.playerCount(),
                            u.maxPlayers(),
                            u.gameVersion() != null ? u.gameVersion() : ""));
                    changed = true;
                } else if (removed) {
                    changed = true;
                }
            }
        }
        if (changed) {
            fireChanged();
        }
    }

    private void fireChanged() {
        List<Listener> snapshot = List.copyOf(listeners);
        if (snapshot.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                for (Listener l : snapshot) {
                    l.friendsChanged();
                }
            });
        } else {
            for (Listener l : snapshot) {
                l.friendsChanged();
            }
        }
    }
}
