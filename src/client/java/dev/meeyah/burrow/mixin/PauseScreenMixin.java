package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.session.HostSession;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Repurposes the Pause Menu's "Open to LAN / Multiplayer Options" button as
 * Burrow's "Host World" control panel.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin {
    @Shadow
    @Final
    private static Component MULTIPLAYER_OPTIONS;

    @ModifyArg(
            method = "createPauseMenu",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/PauseScreen;openScreenButton(Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;)Lnet/minecraft/client/gui/components/Button;"
            ),
            index = 0
    )
    private Component burrow$relabelHostWorldButton(Component label) {
        if (label == MULTIPLAYER_OPTIONS) {
            return Component.literal(HostSession.isHosting() ? "Multiplayer Settings" : "Host World");
        }
        return label;
    }
}
