package dev.meeyah.burrow.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.meeyah.burrow.ui.BurrowToasts;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the player press [Y] while a Burrow notification toast is on screen
 * to immediately accept a world invite, send an invite in response to a
 * request, or accept a friend request — works on the Title Screen, in menus,
 * and in-game, while staying completely out of the way of chat/text inputs.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void burrow$handleToastQuickAccept(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action != InputConstants.PRESS || event.key() != InputConstants.KEY_Y || event.modifiers() != 0) {
            return;
        }
        if (!BurrowToasts.hasActivePrompt()) {
            return;
        }
        Screen screen = this.minecraft.gui == null ? null : this.minecraft.gui.screen();
        if (screen != null) {
            if (screen instanceof ChatScreen
                    || screen instanceof AbstractSignEditScreen
                    || screen instanceof BookEditScreen
                    || screen.getFocused() instanceof EditBox) {
                return;
            }
        }
        if (BurrowToasts.triggerActivePrompt()) {
            ci.cancel();
        }
    }
}
