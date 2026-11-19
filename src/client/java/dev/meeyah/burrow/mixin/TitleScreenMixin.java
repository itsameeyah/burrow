package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.ui.BurrowFriendsScreen;
import dev.meeyah.burrow.ui.BurrowUi;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a compact 20x20 vanilla-styled Friends icon button beside the
 * Multiplayer button on the Title Screen (aligned with the right-side
 * Accessibility icon column) that opens Burrow's Friends screen directly.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void burrow$addFriendsButton(CallbackInfo ci) {
        int x = this.width / 2 + 104;
        int y = this.height / 4 + 48 + 24;
        this.addRenderableWidget(new BurrowUi.FriendsButton(
                x, y,
                () -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new BurrowFriendsScreen(this));
                    }
                }));
    }
}
