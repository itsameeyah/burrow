package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.session.JoinFlow;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * A Burrow join uses a fake "<uuid>.burrow" address. At the resolve step,
 * rewrite it to loopback so vanilla's resolver succeeds; the Connection
 * mixin is what actually steers the socket into the tunnel.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.ConnectScreen$1")
public abstract class ConnectScreenMixin {
    @ModifyArg(
            method = "run",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/resolver/ServerNameResolver;resolveAddress(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;"
            )
    )
    private ServerAddress burrow$pointBurrowAddressAtLoopback(ServerAddress address) {
        if (JoinFlow.active() == null) {
            return address;
        }
        String host = address.getHost();
        if (host != null && host.endsWith(".burrow")) {
            return new ServerAddress("127.0.0.1", address.getPort());
        }
        return address;
    }
}
