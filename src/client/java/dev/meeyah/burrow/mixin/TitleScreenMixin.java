package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.ui.BurrowFriendsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a vanilla-styled "Friends" button next to Multiplayer on the Title
 * Screen that opens Burrow's Friends screen directly.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void burrow$addFriendsButton(CallbackInfo ci) {
        int y = this.height / 4 + 48 + 24;
        int x = this.width / 2 + 104;
        addRenderableWidget(Button.builder(Component.literal("Friends"), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new BurrowFriendsScreen(this));
            }
        }).bounds(x, y, 64, 20).build());
    }
}
