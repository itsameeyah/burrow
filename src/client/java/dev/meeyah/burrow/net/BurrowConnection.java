package dev.meeyah.burrow.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.config.BurrowConfig;
import dev.meeyah.burrow.dev.DevIdentity;
import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.session.JoinFlow;
import dev.meeyah.burrow.state.FriendsState;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Owns the signaling websocket: connect, handshake, dispatch incoming ops
 * onto the friends state, and reconnect with capped exponential backoff.
 */
public final class BurrowConnection {
    private static final Logger LOG = LoggerFactory.getLogger("Burrow");

    public enum Status { OFFLINE, CONNECTING, CONNECTED, ERROR }

    private final BurrowConfig config;
    private final FriendsState state;
    private final java.util.concurrent.atomic.AtomicBoolean reconnectScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile WebSocketClient client;
    private volatile Status status = Status.OFFLINE;
    private volatile boolean stopped = false;
    private volatile int attempts = 0;

    public BurrowConnection(BurrowConfig config, FriendsState state) {
        this.config = config;
        this.state = state;
    }

    public Status status() {
        return status;
    }

    public synchronized void connectAsync() {
        if (stopped || status == Status.CONNECTED || status == Status.CONNECTING) {
            return;
        }
        status = Status.CONNECTING;
        Thread worker = new Thread(this::connectBlocking, "burrow-connect");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        stopped = true;
        WebSocketClient ws = client;
        if (ws != null) {
            ws.close();
        }
        status = Status.OFFLINE;
    }

    private void connectBlocking() {
        URI uri;
        try {
            uri = URI.create(BurrowClient.signalingUrl());
        } catch (Exception e) {
            LOG.warn("invalid Burrow server URL '{}'", BurrowClient.signalingUrl(), e);
            status = Status.ERROR;
            return;
        }
        WebSocketClient ws = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                attempts = 0;
                JsonObject hello = Protocol.msg(Protocol.OP_HELLO);
                if (DevIdentity.isDev()) {
                    String name = DevIdentity.username();
                    hello.addProperty("mode", "dev");
                    hello.addProperty("name", name);
                    hello.addProperty("uuid", DevIdentity.offlineUuid(name).toString());
                } else {
                    // Real Microsoft/Xbox authentication is wired up in a later milestone.
                    hello.addProperty("mode", "anonymous");
                }
                send(hello.toString());
            }

            @Override
            public void onMessage(String message) {
                try {
                    handle(JsonParser.parseString(message).getAsJsonObject());
                } catch (Exception e) {
                    LOG.warn("unreadable message from Burrow server: {}", message, e);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                status = Status.OFFLINE;
                if (!stopped) {
                    scheduleReconnect();
                }
            }

            @Override
            public void onError(Exception ex) {
                // Handled through onClose; logged here for diagnostics.
                LOG.debug("websocket error", ex);
            }
        };
        ws.setDaemon(true);
        this.client = ws;
        try {
            if (!ws.connectBlocking(10, TimeUnit.SECONDS)) {
                status = Status.ERROR;
                scheduleReconnect();
            }
        } catch (Exception e) {
            status = Status.ERROR;
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (stopped) {
            return;
        }
        // Exactly one reconnect sleeper at a time: both the connectBlocking
        // failure path and the socket's own onClose can land here after a
        // failed attempt, and they must not double-schedule.
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        attempts = Math.min(attempts + 1, 6);
        long delayMillis = Math.min(1000L << attempts, 60000L);
        Thread sleeper = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reconnectScheduled.set(false);
                return;
            }
            reconnectScheduled.set(false);
            connectAsync();
        }, "burrow-reconnect");
        sleeper.setDaemon(true);
        sleeper.start();
    }

    private void handle(JsonObject msg) {
        String op = msg.has("op") ? msg.get("op").getAsString() : "";
        switch (op) {
            case Protocol.OP_WELCOME -> {
                UUID self = msg.has("uuid") ? UUID.fromString(msg.get("uuid").getAsString()) : null;
                String name = msg.has("name") ? msg.get("name").getAsString() : "?";
                state.setSelf(self, name);
                status = Status.CONNECTED;
                LOG.info("connected to Burrow server as {}", name);
                // If we were hosting before a reconnect, re-announce the
                // session so friends still see the world as live.
                HostSession.reannounce();
            }
            case Protocol.OP_FRIENDS_SYNC -> {
                List<FriendsState.Friend> friends = parseFriends(array(msg, "friends"));
                List<FriendsState.Request> incoming = parseRequests(array(msg, "incoming"), false);
                List<FriendsState.Request> outgoing = parseRequests(array(msg, "outgoing"), true);
                List<FriendsState.DiscoverableWorld> discoverable = parseDiscoverable(array(msg, "discoverable"));
                state.replaceAll(friends, incoming, outgoing, discoverable);
            }
            case Protocol.OP_PRESENCE_BATCH -> state.applyPresence(parsePresence(array(msg, "updates")));
            case Protocol.OP_SESSION_STATE -> state.applySessions(parseSessions(array(msg, "sessions")));
            case Protocol.OP_INVITE_RECEIVED -> {
                UUID host = uuid(msg, "host");
                if (host != null) {
                    state.addInvite(new FriendsState.WorldInvite(
                            host,
                            strOr(msg, "hostName", "?"),
                            strOr(msg, "worldName", "a world"),
                            strOr(msg, "invitedByName", "?")));
                }
            }
            case Protocol.OP_JOIN_READY -> JoinFlow.onJoinReady(
                    uuid(msg, "host"),
                    str(msg, "worldName"),
                    str(msg, "roomId"),
                    str(msg, "token"),
                    str(msg, "relayPath"));
            case Protocol.OP_RELAY_HOST -> HostSession.onRelayHost(
                    str(msg, "roomId"),
                    str(msg, "token"),
                    str(msg, "relayPath"),
                    uuid(msg, "joinerUuid"),
                    str(msg, "joinSource"),
                    str(msg, "viaName"));
            case Protocol.OP_ERROR -> {
                String message = msg.has("message") ? msg.get("message").getAsString() : msg.toString();
                LOG.warn("server error: {}", message);
                state.setError(message);
            }
            default -> LOG.debug("unknown op: {}", op);
        }
    }

    private static JsonArray array(JsonObject msg, String member) {
        return msg.has(member) && msg.get(member).isJsonArray() ? msg.getAsJsonArray(member) : new JsonArray();
    }

    private static String str(JsonObject o, String member) {
        return o.has(member) && !o.get(member).isJsonNull() ? o.get(member).getAsString() : null;
    }

    private static UUID uuid(JsonObject o, String member) {
        String s = str(o, member);
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<FriendsState.Friend> parseFriends(JsonArray arr) {
        List<FriendsState.Friend> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            out.add(new FriendsState.Friend(
                    uuid(o, "uuid"),
                    strOr(o, "name", "?"),
                    strOr(o, "presence", "offline"),
                    bool(o, "hosting"),
                    strOr(o, "worldName", ""),
                    strOr(o, "access", ""),
                    bool(o, "locked"),
                    intVal(o, "playerCount"),
                    intVal(o, "maxPlayers")));
        }
        return out;
    }

    private static List<FriendsState.DiscoverableWorld> parseDiscoverable(JsonArray arr) {
        List<FriendsState.DiscoverableWorld> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            UUID host = uuid(o, "host");
            if (host == null) continue;
            out.add(new FriendsState.DiscoverableWorld(
                    host,
                    strOr(o, "hostName", "?"),
                    strOr(o, "worldName", "a world"),
                    strOr(o, "access", ""),
                    strOr(o, "source", ""),
                    strOr(o, "viaName", ""),
                    bool(o, "locked"),
                    intVal(o, "playerCount"),
                    intVal(o, "maxPlayers")));
        }
        return out;
    }

    private static List<FriendsState.Request> parseRequests(JsonArray arr, boolean outgoing) {
        List<FriendsState.Request> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            out.add(new FriendsState.Request(uuid(o, "uuid"), strOr(o, "name", "?"), outgoing));
        }
        return out;
    }

    private static List<FriendsState.PresenceUpdate> parsePresence(JsonArray arr) {
        List<FriendsState.PresenceUpdate> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            out.add(new FriendsState.PresenceUpdate(
                    uuid(o, "uuid"),
                    strOr(o, "presence", "offline"),
                    bool(o, "hosting"),
                    str(o, "worldName")));
        }
        return out;
    }

    private static List<FriendsState.SessionUpdate> parseSessions(JsonArray arr) {
        List<FriendsState.SessionUpdate> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            UUID host = uuid(o, "host");
            if (host == null) {
                host = uuid(o, "uuid");
            }
            out.add(new FriendsState.SessionUpdate(
                    host,
                    strOr(o, "hostName", "?"),
                    bool(o, "active"),
                    strOr(o, "worldName", ""),
                    strOr(o, "access", ""),
                    strOr(o, "source", ""),
                    strOr(o, "viaName", ""),
                    bool(o, "locked"),
                    intVal(o, "playerCount"),
                    intVal(o, "maxPlayers")));
        }
        return out;
    }

    private static int intVal(JsonObject o, String member) {
        try {
            return o.has(member) && o.get(member).isJsonPrimitive() ? o.get(member).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String strOr(JsonObject o, String member, String fallback) {
        String s = str(o, member);
        return s == null ? fallback : s;
    }

    /** Null-safe boolean field read (absent or JSON null -> false). */
    private static boolean bool(JsonObject o, String member) {
        return o.has(member) && o.get(member).isJsonPrimitive() && o.get(member).getAsBoolean();
    }

    // ----- outgoing actions (used by the UI) -----

    public boolean send(JsonObject msg) {
        WebSocketClient ws = client;
        if (ws == null || !ws.isOpen()) {
            return false;
        }
        try {
            ws.send(msg.toString());
            return true;
        } catch (Exception e) {
            LOG.debug("failed to send {}", msg.get("op"), e);
            return false;
        }
    }

    public void addFriend(String name) {
        send(Protocol.friendAdd(name));
    }

    public void acceptRequest(UUID requestId) {
        send(Protocol.friendAccept(requestId));
    }

    public void declineRequest(UUID requestId) {
        send(Protocol.friendDecline(requestId));
    }

    public void removeFriend(UUID friendId) {
        send(Protocol.friendRemove(friendId));
    }

    public void joinBegin(UUID hostId) {
        send(Protocol.joinBegin(hostId));
    }

    public void sessionOpen(
            String worldName,
            String gameVersion,
            String access,
            String invitePerm,
            boolean locked,
            int maxPlayers,
            int playerCount,
            java.util.Collection<UUID> operators,
            java.util.Collection<UUID> banned
    ) {
        send(Protocol.sessionPayload(
                Protocol.OP_SESSION_OPEN,
                worldName, gameVersion, "none",
                access, invitePerm, locked, maxPlayers, playerCount,
                operators, banned));
    }

    public void sessionUpdate(
            String worldName,
            String gameVersion,
            String access,
            String invitePerm,
            boolean locked,
            int maxPlayers,
            int playerCount,
            java.util.Collection<UUID> operators,
            java.util.Collection<UUID> banned
    ) {
        send(Protocol.sessionPayload(
                Protocol.OP_SESSION_UPDATE,
                worldName, gameVersion, "none",
                access, invitePerm, locked, maxPlayers, playerCount,
                operators, banned));
    }

    public void sessionInvite(UUID targetId, @org.jetbrains.annotations.Nullable UUID hostId) {
        send(Protocol.sessionInvite(targetId, hostId));
    }

    public void sessionClose() {
        send(Protocol.sessionClose());
    }
}
