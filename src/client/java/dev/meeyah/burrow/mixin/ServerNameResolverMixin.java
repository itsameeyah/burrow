package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.session.JoinFlow;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * A Burrow join uses a fake "<uuid>.burrow" address. At the resolver — a
 * named, stable vanilla class rather than any anonymous inner class —
 * rewrite it to loopback so resolution succeeds; the Connection mixin is
 * what actually steers the socket into the tunnel. Only ever active while
 * a live, unexpired Burrow join exists.
 */
@Mixin(ServerNameResolver.class)
public abstract class ServerNameResolverMixin {
    @ModifyVariable(method = "resolveAddress", at = @At("HEAD"), argsOnly = true)
    private ServerAddress burrow$pointBurrowAddressAtLoopback(ServerAddress address) {
        if (JoinFlow.peekActive() == null) {
            return address;
        }
        String host = address.getHost();
        if (host != null && host.endsWith(".burrow")) {
            return new ServerAddress("127.0.0.1", address.getPort());
        }
        return address;
    }
}
