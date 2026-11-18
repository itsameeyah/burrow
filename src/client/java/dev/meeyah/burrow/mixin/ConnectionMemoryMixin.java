package dev.meeyah.burrow.mixin;

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
 * compression for them. Burrow's host-side endpoint rides a LocalChannel
 * too — but its bytes come from a remote friend through the relay, so those
 * connections must behave exactly like real LAN sockets.
 *
 * This mixin marks only the connections terminating at Burrow's private
 * host endpoint as remote; every other memory connection (vanilla
 * singleplayer's own) is untouched.
 */
@Mixin(Connection.class)
public abstract class ConnectionMemoryMixin {
    @Shadow
    private volatile Channel channel;

    @Inject(method = "isMemoryConnection", at = @At("HEAD"), cancellable = true)
    private void burrow$tunnelConnectionsAreRemote(CallbackInfoReturnable<Boolean> cir) {
        Channel ch = this.channel;
        if (ch instanceof LocalChannel
                && ch.localAddress() instanceof LocalAddress local
                && HostSession.HOST_ADDRESS_ID.equals(local.id())) {
            cir.setReturnValue(false);
        }
    }
}
