package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.session.HostPermissions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

/**
 * Prevents Visitors in a Burrow-hosted world from opening container menus
 * (chests, barrels, furnaces, villagers, etc.) or dropping items.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "openMenu", at = @At("HEAD"), cancellable = true)
    private void burrow$blockVisitorMenus(MenuProvider provider, CallbackInfoReturnable<OptionalInt> cir) {
        if (HostPermissions.isVisitor((ServerPlayer) (Object) this)) {
            cir.setReturnValue(OptionalInt.empty());
        }
    }

    @Inject(
            method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void burrow$blockVisitorDrop(
            ItemStack stack,
            boolean throwRandomly,
            boolean retainOwnership,
            CallbackInfoReturnable<ItemEntity> cir
    ) {
        if (HostPermissions.isVisitor((ServerPlayer) (Object) this)) {
            cir.setReturnValue(null);
        }
    }
}
