package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.ui.BurrowFriendsScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a clean, vanilla-matching "Friends" button beside the Multiplayer
 * button on the Title Screen that opens Burrow's friends modal directly.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin() {
        super(Component.empty());
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void burrow$addFriendsButton(CallbackInfo ci) {
        Component multiplayerLabel = Component.translatable("menu.multiplayer");
        int targetY = this.height / 4 + 48 + 24;
        for (GuiEventListener child : this.children()) {
            if (child instanceof AbstractWidget widget && multiplayerLabel.equals(widget.getMessage())) {
                targetY = widget.getY();
                break;
            }
        }
        this.addRenderableWidget(Button.builder(Component.literal("Friends"), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new BurrowFriendsScreen(this));
            }
        }).bounds(this.width / 2 + 104, targetY, 64, 20).build());
    }
}
