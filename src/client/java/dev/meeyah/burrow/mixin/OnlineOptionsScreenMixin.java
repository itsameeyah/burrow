package dev.meeyah.burrow.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OnlineOptionsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The vanilla friends button funnels through a Microsoft-account opt-in
 * gate before opening the friends list. Burrow replaces the entire feature
 * with its own backend, so the vanilla gate (and the Xbox service behind
 * it) is bypassed: the requested open action always runs immediately.
 */
@Mixin(OnlineOptionsScreen.class)
public abstract class OnlineOptionsScreenMixin {
    @Inject(method = "confirmFriendsListEnabled", at = @At("HEAD"), cancellable = true)
    private static void burrow$skipVanillaGate(Minecraft minecraft, Runnable onEnabled, Screen lastScreen, CallbackInfo ci) {
        ci.cancel();
        onEnabled.run();
    }
}
