package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.net.RelayTunnel;
import dev.meeyah.burrow.session.HostSession;
import io.netty.channel.Channel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla treats LocalChannel connections as trusted in-process "memory"
 * connections and skips Mojang authentication, encryption negotiation and
 * compression for them. Both ends of a Burrow tunnel ride a LocalChannel
 * too — but their bytes cross the relay to/from a remote peer, so both
 * host-side and joiner-side tunnel connections must behave like real remote
 * sockets.
 *
 * This mixin marks only Burrow's host and joiner tunnel connections as
 * remote; vanilla singleplayer's own memory connection is untouched.
 */
@Mixin(Connection.class)
public abstract class ConnectionMemoryMixin {
    @Shadow
    private volatile Channel channel;

    @Inject(method = "isMemoryConnection", at = @At("HEAD"), cancellable = true)
    private void burrow$tunnelConnectionsAreRemote(CallbackInfoReturnable<Boolean> cir) {
        Channel ch = this.channel;
        if (!(ch instanceof LocalChannel)) {
            return;
        }
        boolean isHostTunnel = ch.localAddress() instanceof LocalAddress local
                && HostSession.HOST_ADDRESS_ID.equals(local.id());
        boolean isJoinTunnel = ch.remoteAddress() instanceof LocalAddress remote
                && remote.id().startsWith(RelayTunnel.JOIN_ADDRESS_PREFIX);
        if (isHostTunnel || isJoinTunnel) {
            cir.setReturnValue(false);
        }
    }
}
