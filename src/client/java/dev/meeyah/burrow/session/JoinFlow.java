package dev.meeyah.burrow.session;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.net.RelayTunnel;
import dev.meeyah.burrow.state.FriendsState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Joiner side of Burrow: click a hosting friend, ask the Burrow server for
 * a relay room, build the tunnel, then let vanilla's own connect screen do
 * everything it normally does — our mixins quietly swap the transport to
 * the tunnel while the address resolver reads a harmless 127.0.0.1.
 */
public final class JoinFlow {
    private static volatile @Nullable RelayTunnel active;

    private JoinFlow() {}

    /** Set by our Connection mixin: non-null means the current vanilla connect should ride the tunnel. */
    public static @Nullable RelayTunnel active() {
        return active;
    }

    public static void begin(FriendsState.Friend host) {
        BurrowConnectionHelper.begin(host.uuid());
    }

    public static void onJoinReady(UUID hostUuid, String worldName, String roomId, String token, String relayPath) {
        String hostName = lookupName(hostUuid);
        String url = HostSession.relayUrl(relayPath, roomId, token);
        Thread worker = new Thread(() -> {
            RelayTunnel tunnel = new RelayTunnel(RelayTunnel.Mode.JOIN, url, null, JoinFlow::tunnelClosed);
            try {
                tunnel.connectBlocking(10);
            } catch (InterruptedException e) {
                tunnel.close();
                return;
            }
            active = tunnel;
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                String fakeAddress = hostUuid + ".burrow";
                ServerAddress address = new ServerAddress(fakeAddress, 25565);
                ServerData data = new ServerData(
                        hostName == null ? "Friend's world" : hostName + " — " + worldName,
                        fakeAddress + ":" + 25565,
                        ServerData.Type.LAN);
                ConnectScreen.startConnecting(mc.gui.screen(), mc, address, data, false, null);
            });
        }, "burrow-join");
        worker.setDaemon(true);
        worker.start();
    }

    private static void tunnelClosed() {
        active = null;
    }

    private static String lookupName(UUID uuid) {
        for (FriendsState.Friend f : BurrowClient.friends().friends()) {
            if (uuid.equals(f.uuid())) {
                return f.name();
            }
        }
        return null;
    }

    /** Small indirection so the connection layer can kick off a join without a circular import mess. */
    private static final class BurrowConnectionHelper {
        static void begin(UUID hostId) {
            if (BurrowClient.connection() != null) {
                BurrowClient.connection().joinBegin(hostId);
            }
        }
    }
}
