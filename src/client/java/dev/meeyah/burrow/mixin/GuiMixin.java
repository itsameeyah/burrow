package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.ui.BurrowFriendsScreen;
import dev.meeyah.burrow.ui.HostWorldScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.WorldOptionsScreen;
import net.minecraft.client.gui.screens.friends.FriendsOverlayScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Swaps vanilla's Friends overlay for Burrow's friends screen, and vanilla's
 * Multiplayer Options (Open to LAN) screen for Burrow's Host World control
 * panel. The screen live at swap time is passed as the parent so Done/Escape
 * returns where the player came from.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen burrow$replaceScreens(Screen screen) {
        if (screen instanceof FriendsOverlayScreen) {
            Screen parent = ((Gui) (Object) this).screen();
            return new BurrowFriendsScreen(parent);
        }
        if (screen instanceof WorldOptionsScreen) {
            Screen parent = ((Gui) (Object) this).screen();
            return new HostWorldScreen(parent);
        }
        return screen;
    }
}
