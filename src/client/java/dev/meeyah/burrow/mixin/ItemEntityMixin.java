package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.session.HostPermissions;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents Visitors in a Burrow-hosted world from picking up dropped items. */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void burrow$blockVisitorPickup(Player player, CallbackInfo ci) {
        if (HostPermissions.isVisitor(player)) {
            ci.cancel();
        }
    }
}
