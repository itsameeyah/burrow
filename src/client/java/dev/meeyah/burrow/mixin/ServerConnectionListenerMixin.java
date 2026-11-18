package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.session.HostSession;
import io.netty.channel.ChannelHandler;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Whenever the game binds its server channel (LAN publish), remember the
 * vanilla channel initializer so Burrow can attach a private in-process
 * endpoint with the identical pipeline later. The argument passes through
 * unchanged — this mixin only observes.
 */
@Mixin(ServerConnectionListener.class)
public abstract class ServerConnectionListenerMixin {
    @ModifyArg(
            method = "startTcpServerListener",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/netty/bootstrap/ServerBootstrap;childHandler(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/ServerBootstrap;"
            )
    )
    private ChannelHandler burrow$rememberServerInitializer(ChannelHandler childHandler) {
        HostSession.rememberInitializer(childHandler);
        return childHandler;
    }
}
