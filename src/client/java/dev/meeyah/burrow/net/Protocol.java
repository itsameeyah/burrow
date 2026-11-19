package dev.meeyah.burrow.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.UUID;

/**
 * The Burrow wire protocol.
 *
 * Every message is a JSON object carrying an "op" string plus readable,
 * fully-spelled-out fields. This protocol is Burrow's own design: plain
 * JSON-over-WebSocket for signaling, with the relay carrying opaque,
 * already-encrypted Minecraft bytes.
 */
public final class Protocol {
    // Client -> server
    public static final String OP_HELLO = "hello";
    public static final String OP_FRIEND_ADD = "friend.add";
    public static final String OP_FRIEND_ACCEPT = "friend.accept";
    public static final String OP_FRIEND_DECLINE = "friend.decline";
    public static final String OP_FRIEND_REMOVE = "friend.remove";
    public static final String OP_SESSION_OPEN = "session.open";
    public static final String OP_SESSION_UPDATE = "session.update";
    public static final String OP_SESSION_CLOSE = "session.close";
    public static final String OP_SESSION_INVITE = "session.invite";
    public static final String OP_JOIN_BEGIN = "join.begin";

    // Server -> client
    public static final String OP_WELCOME = "welcome";
    public static final String OP_FRIENDS_SYNC = "friends.sync";
    public static final String OP_PRESENCE_BATCH = "presence.batch";
    public static final String OP_SESSION_STATE = "session.state";
    public static final String OP_INVITE_RECEIVED = "invite.received";
    public static final String OP_JOIN_READY = "join.ready";
    public static final String OP_RELAY_HOST = "relay.host";
    public static final String OP_ERROR = "error";

    private Protocol() {}

    /** New message envelope with the given op. */
    public static JsonObject msg(String op) {
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        return o;
    }

    public static JsonObject friendAdd(String name) {
        JsonObject o = msg(OP_FRIEND_ADD);
        o.addProperty("name", name);
        return o;
    }

    public static JsonObject friendAccept(UUID requestId) {
        JsonObject o = msg(OP_FRIEND_ACCEPT);
        o.addProperty("uuid", requestId.toString());
        return o;
    }

    public static JsonObject friendDecline(UUID requestId) {
        JsonObject o = msg(OP_FRIEND_DECLINE);
        o.addProperty("uuid", requestId.toString());
        return o;
    }

    public static JsonObject friendRemove(UUID friendId) {
        JsonObject o = msg(OP_FRIEND_REMOVE);
        o.addProperty("uuid", friendId.toString());
        return o;
    }

    /** Host announces or updates a shareable world session. */
    public static JsonObject sessionPayload(
            String op,
            String worldName,
            String gameVersion,
            String modsHash,
            String access,
            String invitePerm,
            boolean locked,
            int maxPlayers,
            int playerCount,
            Collection<UUID> operators,
            Collection<UUID> banned
    ) {
        JsonObject o = msg(op);
        o.addProperty("worldName", worldName);
        o.addProperty("gameVersion", gameVersion);
        o.addProperty("modsHash", modsHash);
        o.addProperty("access", access);
        o.addProperty("invitePerm", invitePerm);
        o.addProperty("locked", locked);
        o.addProperty("maxPlayers", maxPlayers);
        o.addProperty("playerCount", playerCount);
        JsonArray opsArr = new JsonArray();
        for (UUID u : operators) opsArr.add(u.toString());
        o.add("operators", opsArr);
        JsonArray banArr = new JsonArray();
        for (UUID u : banned) banArr.add(u.toString());
        o.add("banned", banArr);
        return o;
    }

    public static JsonObject sessionClose() {
        return msg(OP_SESSION_CLOSE);
    }

    public static JsonObject sessionInvite(UUID targetId, @Nullable UUID hostId) {
        JsonObject o = msg(OP_SESSION_INVITE);
        o.addProperty("uuid", targetId.toString());
        if (hostId != null) {
            o.addProperty("host", hostId.toString());
        }
        return o;
    }

    /** Ask to join a friend's hosted world; the reply is join.ready with relay details. */
    public static JsonObject joinBegin(UUID hostId) {
        JsonObject o = msg(OP_JOIN_BEGIN);
        o.addProperty("host", hostId.toString());
        return o;
    }
}
