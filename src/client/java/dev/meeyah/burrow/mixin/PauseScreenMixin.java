package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.ui.BurrowFriendsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Repurposes the Pause Menu's "Open to LAN" button as Burrow's "Host World" /
 * "Host Settings" control panel (and keeps it accessible while hosting), and
 * adds a vanilla-styled "Friends" button above Disconnect.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    @Shadow
    @Final
    private static Component SHARE_TO_LAN;

    protected PauseScreenMixin(Component title) {
        super(title);
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

    @Redirect(
            method = "createPauseMenu",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/server/IntegratedServer;isPublished()Z"
            )
    )
    private boolean burrow$keepHostButtonInSingleplayer(IntegratedServer server) {
        return false;
    }

    @Redirect(
            method = "createPauseMenu",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;I)Lnet/minecraft/client/gui/layouts/LayoutElement;"
            )
    )
    private LayoutElement burrow$insertFriendsBeforeDisconnect(
            GridLayout.RowHelper rowHelper,
            LayoutElement disconnectButton,
            int columns
    ) {
        rowHelper.addChild(
                Button.builder(Component.literal("Friends"), b -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new BurrowFriendsScreen(this));
                    }
                }).width(204).build(),
                2
        );
        return rowHelper.addChild(disconnectButton, columns);
    }
}
