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
 * vanilla pipeline (framing, compression, Mojang auth, encryption) runs
 * unchanged on top of whatever bytes the tunnel carries.
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
        return JoinFlow.active() != null ? EventLoopGroupHolder.local() : holder;
    }

    @Redirect(
            method = CONNECT,
            at = @At(
                    value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;"
            )
    )
    private static ChannelFuture burrow$connectThroughTunnel(Bootstrap bootstrap, InetAddress address, int port) {
        RelayTunnel tunnel = JoinFlow.active();
        if (tunnel != null) {
            return bootstrap.connect(tunnel.gameSideAddress());
        }
        return bootstrap.connect(address, port);
    }
}
