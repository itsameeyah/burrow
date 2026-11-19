package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.ui.BurrowFriendsScreen;
import dev.meeyah.burrow.ui.BurrowUi;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Repurposes the Pause Menu's "Open to LAN" slot as Burrow's "Host World" /
 * "Host Settings" panel (keeping it accessible while hosting), and adds a
 * compact 20x20 Friends icon button beside the Options / Host World row.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    @Shadow
    @Final
    private static Component SHARE_TO_LAN;

    @Shadow
    private Button disconnectButton;

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Redirect(
            method = "createPauseMenu",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/server/IntegratedServer;isPublished()Z"
            )
    )
    private boolean burrow$keepHostButtonWhenPublished(IntegratedServer server) {
        return false;
    }

    @ModifyArg(
            method = "createPauseMenu",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/PauseScreen;openScreenButton(Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;)Lnet/minecraft/client/gui/components/Button;"
            ),
            index = 0
    )
    private Component burrow$relabelHostWorldButton(Component label) {
        if (label == SHARE_TO_LAN) {
            return Component.literal(HostSession.isHosting() ? "Host Settings" : "Host World");
        }
        return label;
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
