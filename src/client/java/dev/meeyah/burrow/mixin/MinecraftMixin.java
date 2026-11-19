package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.ui.HostWorldScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Swaps vanilla's "Open to LAN" ({@link ShareToLanScreen}) for Burrow's
 * {@link HostWorldScreen}. The screen live at swap time is passed as the
 * parent so Done/Escape returns where the player came from.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow
    @Nullable
    public Screen screen;

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen burrow$replaceScreens(Screen nextScreen) {
        if (nextScreen instanceof ShareToLanScreen) {
            return new HostWorldScreen(this.screen);
        }
        return nextScreen;
    }
}
