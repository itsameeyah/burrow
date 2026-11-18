package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.net.RelayTunnel;
import dev.meeyah.burrow.session.JoinFlow;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import net.minecraft.network.Connection;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.net.InetAddress;

/**
 * While a Burrow join is active, swap vanilla's remote transport for the
 * local one and steer the connect into the tunnel's local listener. The
 * join is consumed exactly once and only steers loopback-bound connects,
 * so a cancelled or stale join can never hijack a normal server, LAN or
 * Realms connection.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    private static final String CONNECT = "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/server/network/EventLoopGroupHolder;Lnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;";

    @ModifyVariable(
            method = CONNECT,
            at = @At("HEAD"),
            argsOnly = true
    )
    private static EventLoopGroupHolder burrow$useLocalTransport(EventLoopGroupHolder holder) {
        return JoinFlow.peekActive() != null ? EventLoopGroupHolder.local() : holder;
    }

    @Redirect(
            method = CONNECT,
            at = @At(
                    value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;"
            )
    )
    private static ChannelFuture burrow$connectThroughTunnel(Bootstrap bootstrap, InetAddress address, int port) {
        RelayTunnel tunnel = JoinFlow.consumeActive();
        // The loopback check matches the address our resolver rewrite set;
        // a normal internet server address would never take this branch.
        if (tunnel != null && address.isLoopbackAddress()) {
            return bootstrap.connect(tunnel.gameSideAddress());
        }
        return bootstrap.connect(address, port);
    }
}
