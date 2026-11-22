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
import dev.meeyah.burrow.state.ActivityTracker;
import dev.meeyah.burrow.state.FriendsState;
import dev.meeyah.burrow.ui.BurrowToasts;
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
    private static final long HANDSHAKE_TIMEOUT_MS = 25_000L;

    public enum Status { OFFLINE, CONNECTING, CONNECTED, ERROR }

    private final BurrowConfig config;
    private final FriendsState state;
    private final java.util.concurrent.atomic.AtomicBoolean reconnectScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Thread reconnectThread;
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

    /** User-triggered immediate retry: cancels any backoff sleep and reconnects now. */
    public synchronized void reconnectNow() {
        if (stopped) {
            return;
        }
        Thread sleeper = this.reconnectThread;
        this.reconnectThread = null;
        if (sleeper != null) {
            sleeper.interrupt();
        }
        reconnectScheduled.set(false);
        WebSocketClient old = this.client;
        this.client = null;
        if (old != null) {
            try {
                old.close();
            } catch (Exception ignored) {
            }
        }
        attempts = 0;
        status = Status.OFFLINE;
        connectAsync();
    }

    public void stop() {
        stopped = true;
        Thread sleeper = this.reconnectThread;
        this.reconnectThread = null;
        if (sleeper != null) {
            sleeper.interrupt();
        }
        WebSocketClient ws = client;
        client = null;
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
                if (client != this || stopped) {
                    close();
                    return;
                }
                startHandshakeWatchdog(this);
                JsonObject hello = Protocol.msg(Protocol.OP_HELLO);
                if (DevIdentity.isDev()) {
                    String name = DevIdentity.username();
                    hello.addProperty("mode", "dev");
                    hello.addProperty("name", name);
                    hello.addProperty("uuid", DevIdentity.offlineUuid(name).toString());
                } else {
                    hello.addProperty("mode", "mojang");
                    hello.addProperty("name", MojangAuth.username());
                    hello.addProperty("uuid", MojangAuth.profileIdString());
                }
                sendHandshake(this, hello);
            }

            @Override
            public void onMessage(String message) {
                if (client != this) {
                    return;
                }
                try {
                    handle(this, JsonParser.parseString(message).getAsJsonObject());
                } catch (Exception e) {
                    LOG.warn("unreadable message from Burrow server: {}", message, e);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                if (client != this) {
                    return;
                }
                client = null;
                boolean wasConnected = (status == Status.CONNECTED);
                if (status != Status.ERROR) {
                    status = Status.OFFLINE;
                }
                if (!stopped) {
                    LOG.info("Burrow signaling connection closed (code={}, remote={}, reason={})",
                            code, remote, reason == null || reason.isEmpty() ? "<none>" : reason);
                    if (wasConnected || status == Status.OFFLINE) {
                        scheduleReconnect(0L);
                    }
                }
            }

            @Override
            public void onError(Exception ex) {
                if (client != this || stopped) {
                    return;
                }
                LOG.warn("Burrow websocket error: {}", ex.toString());
            }
        };
        ws.setDaemon(true);
        ws.setTcpNoDelay(true);
        this.client = ws;
        try {
            if (!ws.connectBlocking(10, TimeUnit.SECONDS)) {
                if (this.client == ws) {
                    this.client = null;
                    LOG.warn("Timed out connecting to Burrow server ({})", uri);
                    status = Status.ERROR;
                    scheduleReconnect(0L);
                }
            }
        } catch (Exception e) {
            if (this.client == ws) {
                this.client = null;
                LOG.warn("Failed connecting to Burrow server ({}): {}", uri, e.toString());
                status = Status.ERROR;
                scheduleReconnect(0L);
            }
        }
    }

    private void startHandshakeWatchdog(WebSocketClient ws) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(HANDSHAKE_TIMEOUT_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!stopped && client == ws && status == Status.CONNECTING) {
                LOG.warn("Burrow handshake timed out after {}s; reconnecting", HANDSHAKE_TIMEOUT_MS / 1000);
                failHandshake(ws, "Connection timed out — retrying...", true, false);
            }
        }, "burrow-handshake-timeout");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void failHandshake(WebSocketClient ws, String message, boolean retryable, boolean rateLimited) {
        if (client != ws) {
            return;
        }
        client = null;
        status = Status.ERROR;
        if (message != null && !message.isBlank()) {
            state.setError(message);
        }
        try {
            ws.close();
        } catch (Exception ignored) {
        }
        if (retryable && !stopped) {
            scheduleReconnect(rateLimited ? 10_000L : 0L);
        }
    }

    private void scheduleReconnect(long minDelayMillis) {
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
        long backoff = Math.min(1000L << attempts, 60_000L);
        long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(250L, 1500L);
        long delayMillis = Math.min(Math.max(backoff, minDelayMillis) + jitter, 65_000L);
        LOG.info("Scheduling Burrow reconnect attempt #{} in {}ms", attempts, delayMillis);
        Thread sleeper = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reconnectScheduled.set(false);
                return;
            }
            if (reconnectThread == Thread.currentThread()) {
                reconnectThread = null;
            }
            reconnectScheduled.set(false);
            connectAsync();
        }, "burrow-reconnect");
        sleeper.setDaemon(true);
        this.reconnectThread = sleeper;
        sleeper.start();
    }

    private void handle(WebSocketClient ws, JsonObject msg) {
        String op = msg.has("op") ? msg.get("op").getAsString() : "";
        switch (op) {
            case Protocol.OP_AUTH_CHALLENGE -> {
                String serverId = str(msg, "serverId");
                MojangAuth.joinServerAsync(
                        serverId,
                        () -> sendHandshake(ws, Protocol.authVerify(serverId)),
                        failure -> {
                            LOG.warn("Mojang authentication failed (retryable={}, rateLimited={}): {}",
                                    failure.retryable(), failure.rateLimited(), failure.message());
                            failHandshake(ws, failure.message(), failure.retryable(), failure.rateLimited());
                        });
            }
            case Protocol.OP_WELCOME -> {
                UUID self = msg.has("uuid") ? UUID.fromString(msg.get("uuid").getAsString()) : null;
                String name = msg.has("name") ? msg.get("name").getAsString() : "?";
                attempts = 0;
                state.setSelf(self, name);
                status = Status.CONNECTED;
                LOG.info("connected to Burrow server as {}", name);
                // If we were hosting before a reconnect, re-announce the
                // session so friends still see the world as live.
                HostSession.reannounce();
                ActivityTracker.pushCurrent();
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
            case Protocol.OP_SESSION_CODE_STATE -> HostSession.onJoinCodeState(
                    str(msg, "code"),
                    bool(msg, "enabled"));
            case Protocol.OP_CODE_INFO -> state.setCodePreview(new FriendsState.CodePreview(
                    strOr(msg, "code", ""),
                    bool(msg, "found"),
                    uuid(msg, "host"),
                    strOr(msg, "hostName", ""),
                    strOr(msg, "worldName", ""),
                    strOr(msg, "gameVersion", ""),
                    intVal(msg, "playerCount"),
                    intVal(msg, "maxPlayers"),
                    bool(msg, "locked"),
                    bool(msg, "isSelf"),
                    strOr(msg, "error", "")));
            case Protocol.OP_FRIEND_ACCEPTED -> {
                UUID who = uuid(msg, "uuid");
                String name = strOr(msg, "name", "");
                if (!name.isBlank()) {
                    BurrowToasts.showFriendAccepted(who, name);
                }
            }
            case Protocol.OP_INVITE_RECEIVED -> {
                UUID host = uuid(msg, "host");
                if (host != null) {
                    String hostName = strOr(msg, "hostName", "?");
                    String worldName = strOr(msg, "worldName", "a world");
                    String invitedByName = strOr(msg, "invitedByName", hostName);
                    int ttlSecs = intVal(msg, "ttlSeconds");
                    long ttlMs = ttlSecs > 0 ? ttlSecs * 1000L : FriendsState.INVITE_TTL_MS;
                    state.addInvite(new FriendsState.WorldInvite(
                            host, hostName, worldName, invitedByName, System.currentTimeMillis() + ttlMs));
                    BurrowToasts.showWorldInvite(host, hostName, worldName, invitedByName);
                }
            }
            case Protocol.OP_INVITE_REQUESTED -> {
                UUID from = uuid(msg, "from");
                if (from != null) {
                    String fromName = strOr(msg, "fromName", "?");
                    state.addInviteRequest(new FriendsState.InviteRequest(
                            from, fromName, System.currentTimeMillis() + FriendsState.INVITE_TTL_MS));
                    BurrowToasts.showInviteRequest(from, fromName);
                }
            }
            case Protocol.OP_USERS_CHECK_RESULT -> {
                List<UUID> uuids = new ArrayList<>();
                for (JsonElement el : array(msg, "uuids")) {
                    if (el.isJsonPrimitive()) {
                        try { uuids.add(UUID.fromString(el.getAsString())); } catch (Exception ignored) {}
                    }
                }
                List<String> names = new ArrayList<>();
                for (JsonElement el : array(msg, "names")) {
                    if (el.isJsonPrimitive()) {
                        names.add(el.getAsString().toLowerCase());
                    }
                }
                state.recordRegisteredUsers(uuids, names);
            }
            case Protocol.OP_JOIN_READY -> JoinFlow.onJoinReady(
                    uuid(msg, "host"),
                    str(msg, "hostName"),
                    str(msg, "worldName"),
                    str(msg, "gameVersion"),
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
            case Protocol.OP_VOICE_READY -> VoiceBridge.onVoiceReady(
                    uuid(msg, "host"),
                    str(msg, "roomId"),
                    str(msg, "token"),
                    str(msg, "relayPath"));
            case Protocol.OP_RELAY_VOICE -> VoiceBridge.onRelayVoice(
                    str(msg, "roomId"),
                    str(msg, "token"),
                    str(msg, "relayPath"),
                    uuid(msg, "joinerUuid"));
            case Protocol.OP_ERROR -> {
                String message = msg.has("message") ? msg.get("message").getAsString() : msg.toString();
                LOG.warn("server error: {}", message);
                if (status == Status.CONNECTING) {
                    boolean busy = message.toLowerCase().contains("busy") || message.contains("429");
                    failHandshake(ws, message, true, busy);
                } else {
                    state.setError(message);
                }
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
                    intVal(o, "maxPlayers"),
                    strOr(o, "activity", ""),
                    strOr(o, "serverName", ""),
                    strOr(o, "serverIp", ""),
                    strOr(o, "activityLabel", ""),
                    strOr(o, "gameVersion", "")));
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
                    intVal(o, "maxPlayers"),
                    strOr(o, "gameVersion", "")));
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
                    str(o, "worldName"),
                    str(o, "activity"),
                    str(o, "serverName"),
                    str(o, "serverIp"),
                    str(o, "activityLabel")));
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
                    intVal(o, "maxPlayers"),
                    strOr(o, "gameVersion", "")));
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

    /** Handshake-only send (hello / auth.verify) permitted while status == CONNECTING. */
    private boolean sendHandshake(WebSocketClient ws, JsonObject msg) {
        if (ws == null || client != ws || !ws.isOpen()) {
            return false;
        }
        try {
            ws.send(msg.toString());
            return true;
        } catch (Exception e) {
            LOG.warn("failed to send handshake {}: {}", msg.get("op"), e.toString());
            return false;
        }
    }

    public boolean send(JsonObject msg) {
        WebSocketClient ws = client;
        if (status != Status.CONNECTED || ws == null || !ws.isOpen()) {
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

    public void sessionCode(String action) {
        send(Protocol.sessionCode(action));
    }

    public void codeLookup(String code) {
        send(Protocol.codeLookup(code));
    }

    public void joinByCode(String code) {
        send(Protocol.joinCode(code));
    }

    public boolean voiceBegin(UUID hostId) {
        return send(Protocol.voiceBegin(hostId));
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
            java.util.Collection<UUID> banned,
            java.util.Collection<UUID> players
    ) {
        send(Protocol.sessionPayload(
                Protocol.OP_SESSION_OPEN,
                worldName, gameVersion, "none",
                access, invitePerm, locked, maxPlayers, playerCount,
                operators, banned, players));
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
            java.util.Collection<UUID> banned,
            java.util.Collection<UUID> players
    ) {
        send(Protocol.sessionPayload(
                Protocol.OP_SESSION_UPDATE,
                worldName, gameVersion, "none",
                access, invitePerm, locked, maxPlayers, playerCount,
                operators, banned, players));
    }

    public void sessionInvite(UUID targetId, @org.jetbrains.annotations.Nullable UUID hostId) {
        send(Protocol.sessionInvite(targetId, hostId));
    }

    public void updatePresence(String activity, String serverName, String serverIp, String label) {
        send(Protocol.presenceUpdate(activity, serverName, serverIp, label));
    }

    public void requestInvite(UUID friendId) {
        send(Protocol.inviteRequest(friendId));
    }

    public void checkUsers(java.util.Collection<UUID> uuids, java.util.Collection<String> names) {
        send(Protocol.usersCheck(uuids, names));
    }

    public void sessionClose() {
        send(Protocol.sessionClose());
    }
}
