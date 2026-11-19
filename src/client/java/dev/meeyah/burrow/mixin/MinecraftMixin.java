package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.ui.HostWorldScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Swaps vanilla's Share to LAN screen for Burrow's Host World control panel.
 * The screen live at swap time is passed as the parent so Done/Escape returns
 * where the player came from.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen burrow$replaceScreens(Screen screen) {
        if (screen instanceof ShareToLanScreen) {
            Screen parent = ((Minecraft) (Object) this).screen;
            return new HostWorldScreen(parent);
        }
        return screen;
    }
}
