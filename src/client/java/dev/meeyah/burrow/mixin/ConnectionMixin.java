package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.net.RelayTunnel;
import dev.meeyah.burrow.session.JoinFlow;
import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalChannel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * While a Burrow join is active and targeting loopback (the address our
 * resolver rewrite produced), atomically claim the tunnel at connect HEAD
 * and route Bootstrap.group(), Bootstrap.channel(), and Bootstrap.connect()
 * through it together — so a non-loopback connect can never end up with a
 * mismatched LocalChannel bootstrap, and a cancelled/stale join can never
 * hijack a normal server, LAN or Realms connection.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Unique
    private static final String CONNECT = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;";

    @Unique
    private static final ThreadLocal<RelayTunnel> BURROW_TUNNEL = new ThreadLocal<>();

    @Inject(method = CONNECT, at = @At("HEAD"))
    private static void burrow$claimTunnelIfLoopback(
            InetSocketAddress address,
            boolean useEpollIfAvailable,
            Connection connection,
            CallbackInfoReturnable<ChannelFuture> cir
    ) {
        BURROW_TUNNEL.remove();
        if (address != null && address.getAddress() != null && address.getAddress().isLoopbackAddress()) {
            RelayTunnel tunnel = JoinFlow.consumeActive();
            if (tunnel != null) {
                BURROW_TUNNEL.set(tunnel);
            }
        }
    }

    @Redirect(
            method = CONNECT,
            at = @At(
                    value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/AbstractBootstrap;"
            )
    )
    private static AbstractBootstrap<Bootstrap, Channel> burrow$useLocalGroup(
            Bootstrap bootstrap,
            EventLoopGroup group
    ) {
        EventLoopGroup targetGroup = BURROW_TUNNEL.get() != null
                ? Connection.LOCAL_WORKER_GROUP.get()
                : group;
        return bootstrap.group(targetGroup);
    }

    @Redirect(
            method = CONNECT,
            at = @At(
                    value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;"
            )
    )
    private static AbstractBootstrap<Bootstrap, Channel> burrow$useLocalChannel(
            Bootstrap bootstrap,
            Class<? extends Channel> channelCls
    ) {
        Class<? extends Channel> targetCls = BURROW_TUNNEL.get() != null
                ? LocalChannel.class
                : channelCls;
        return bootstrap.channel(targetCls);
    }

    @Redirect(
            method = CONNECT,
            at = @At(
                    value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;"
            )
    )
    private static ChannelFuture burrow$connectThroughTunnel(Bootstrap bootstrap, InetAddress address, int port) {
        RelayTunnel tunnel = BURROW_TUNNEL.get();
        BURROW_TUNNEL.remove();
        if (tunnel != null) {
            return bootstrap.connect(tunnel.gameSideAddress());
        }
        return bootstrap.connect(address, port);
    }

    @Inject(method = CONNECT, at = @At("RETURN"))
    private static void burrow$clearTunnel(
            InetSocketAddress address,
            boolean useEpollIfAvailable,
            Connection connection,
            CallbackInfoReturnable<ChannelFuture> cir
    ) {
        BURROW_TUNNEL.remove();
    }
}
