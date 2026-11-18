package dev.meeyah.burrow.state;

import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Client-side model of the friends list. The connection layer mutates it;
 * the friends screen observes it through {@link Listener}.
 */
public final class FriendsState {
    public record Friend(UUID uuid, String name, String presence, boolean hosting, String worldName) {}

    public record Request(UUID uuid, String name, boolean outgoing) {}

    public record PresenceUpdate(UUID uuid, String presence, boolean hosting, String worldName) {}

    public interface Listener {
        void friendsChanged();
    }

    private final List<Friend> friends = new CopyOnWriteArrayList<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile UUID selfUuid;
    private volatile String selfName;
    private volatile String lastError;
    private volatile long lastErrorAt;

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

    public void replaceAll(List<Friend> newFriends, List<Request> incoming, List<Request> outgoing) {
        friends.clear();
        friends.addAll(newFriends);
        requests.clear();
        requests.addAll(incoming);
        requests.addAll(outgoing);
        fireChanged();
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
                            u.worldName() != null ? u.worldName() : f.worldName()));
                    changed = true;
                    break;
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
