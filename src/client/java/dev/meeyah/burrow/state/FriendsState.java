package dev.meeyah.burrow.state;

import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Client-side model of the friends list, incoming/outgoing requests,
 * discoverable non-friend worlds (friends-of-friends / open), and world invites.
 * The connection layer mutates it; screens observe it through {@link Listener}.
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
            int maxPlayers
    ) {
        public Friend(UUID uuid, String name, String presence, boolean hosting, String worldName) {
            this(uuid, name, presence, hosting, worldName, "", false, 0, 0);
        }
    }

    public record Request(UUID uuid, String name, boolean outgoing) {}

    public record DiscoverableWorld(
            UUID host,
            String hostName,
            String worldName,
            String access,
            String source,
            String viaName,
            boolean locked,
            int playerCount,
            int maxPlayers
    ) {}

    public record WorldInvite(
            UUID host,
            String hostName,
            String worldName,
            String invitedByName
    ) {}

    public record PresenceUpdate(UUID uuid, String presence, boolean hosting, String worldName) {}

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
            int maxPlayers
    ) {}

    public interface Listener {
        void friendsChanged();
    }

    private final List<Friend> friends = new CopyOnWriteArrayList<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<DiscoverableWorld> discoverable = new CopyOnWriteArrayList<>();
    private final List<WorldInvite> invites = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile UUID selfUuid;
    private volatile String selfName;
    private volatile String lastError;
    private volatile long lastErrorAt;
    private volatile boolean initialSyncDone = false;

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public List<Friend> friends() {
        return List.copyOf(friends);
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public List<DiscoverableWorld> discoverableWorlds() {
        return List.copyOf(discoverable);
    }

    public List<WorldInvite> worldInvites() {
        return List.copyOf(invites);
    }

    public boolean isFriend(UUID uuid) {
        if (uuid == null) return false;
        for (Friend f : friends) {
            if (uuid.equals(f.uuid())) return true;
        }
        return false;
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
            java.util.Set<UUID> prevIncoming = new java.util.HashSet<>();
            for (Request r : requests) {
                if (!r.outgoing() && r.uuid() != null) {
                    prevIncoming.add(r.uuid());
                }
            }
            for (Request r : incoming) {
                if (!r.outgoing() && r.uuid() != null && !prevIncoming.contains(r.uuid())) {
                    dev.meeyah.burrow.ui.BurrowToasts.showFriendRequest(r.name());
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
                            u.hosting() ? f.maxPlayers() : 0));
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
                            u.active() ? u.maxPlayers() : 0));
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
                            u.maxPlayers()));
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
