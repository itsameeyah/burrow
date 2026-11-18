package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.ui.BurrowFriendsScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.friends.FriendsOverlayScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Whenever anything asks the game to show the vanilla friends overlay —
 * the title-screen button, the pause-menu button, or the O keybind — swap
 * in Burrow's own friends screen instead, before it is ever initialized.
 * The screen that was live at swap time is passed along as the parent, so
 * Done/Escape returns where the player came from.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen burrow$replaceFriendsScreen(Screen screen) {
        if (screen instanceof FriendsOverlayScreen) {
            Screen parent = ((Gui) (Object) this).screen();
            return new BurrowFriendsScreen(parent);
        }
        return screen;
    }
}
