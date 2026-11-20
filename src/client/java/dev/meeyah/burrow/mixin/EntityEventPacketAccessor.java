package dev.meeyah.burrow.mixin;

import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundEntityEventPacket.class)
public interface EntityEventPacketAccessor {
    @Accessor("entityId")
    int burrow$entityId();
}
