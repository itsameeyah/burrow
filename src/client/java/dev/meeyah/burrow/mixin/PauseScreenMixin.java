package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.ui.BurrowFriendsScreen;
import dev.meeyah.burrow.ui.BurrowUi;
import dev.meeyah.burrow.ui.HostWorldScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * Repurposes the Pause Menu's "Open to LAN" slot (and its post-publish
 * replacement slot in singleplayer) as Burrow's "Host World / Host Settings"
 * control panel, and adds a matching "Friends" button beside the menu row.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    @Shadow
    @Final
    private static Component SHARE_TO_LAN;

    @Shadow
    @Final
    private static Component PLAYER_REPORTING;

    @Shadow
    private Button disconnectButton;

    @Shadow
    protected abstract Button openScreenButton(Component label, Supplier<Screen> screenFactory);

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Redirect(
            method = "createPauseMenu",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/PauseScreen;openScreenButton(Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;)Lnet/minecraft/client/gui/components/Button;"
            )
    )
    private Button burrow$repurposeHostWorldButton(PauseScreen instance, Component label, Supplier<Screen> screenFactory) {
        if (this.minecraft != null && this.minecraft.hasSingleplayerServer()
                && (label == SHARE_TO_LAN || label == PLAYER_REPORTING)) {
            Component hostLabel = Component.literal(HostSession.isHosting() ? "Host Settings" : "Host World");
            return this.openScreenButton(hostLabel, () -> new HostWorldScreen(this));
        }
        return this.openScreenButton(label, screenFactory);
    }

    @Inject(method = "createPauseMenu", at = @At("TAIL"))
    private void burrow$addFriendsButton(CallbackInfo ci) {
        if (this.disconnectButton == null) {
            return;
        }
        int x = this.disconnectButton.getX() + this.disconnectButton.getWidth() + 4;
        int y = this.disconnectButton.getY() - 24;
        this.addRenderableWidget(new BurrowUi.FriendsButton(
                x, y,
                () -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new BurrowFriendsScreen(this));
                    }
                }));
    }
}
