package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.compat.ModCompatSanitizer;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Host-side outbound packet hook for Burrow's graceful mod-mismatch fallback.
 *
 * Only active for remote players connected to a Burrow-hosted integrated
 * server who are missing one or more non-vanilla mod namespaces. All other
 * players hit a single concurrent-map lookup returning {@code null} and pass
 * straight through with zero allocation.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerMixin {
    @Unique
    private static final ThreadLocal<Boolean> BURROW_IN_SANITIZER = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Shadow
    public abstract void send(Packet<?> packet, @Nullable ChannelFutureListener listener);

    @Inject(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void burrow$sanitizeMissingModPackets(
            Packet<?> packet,
            @Nullable ChannelFutureListener listener,
            CallbackInfo ci
    ) {
        if (Boolean.TRUE.equals(BURROW_IN_SANITIZER.get())) {
            return;
        }
        if (!((Object) this instanceof ServerGamePacketListenerImpl gameListener)) {
            return;
        }
        BURROW_IN_SANITIZER.set(Boolean.TRUE);
        try {
            Packet<?> sanitized = ModCompatSanitizer.sanitize(gameListener, packet);
            if (sanitized == packet) {
                return;
            }
            ci.cancel();
            if (sanitized != null) {
                this.send(sanitized, listener);
            }
        } finally {
            BURROW_IN_SANITIZER.set(Boolean.FALSE);
        }
    }
}
