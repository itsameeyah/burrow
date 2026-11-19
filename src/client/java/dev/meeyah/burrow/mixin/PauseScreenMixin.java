package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.ui.BurrowFriendsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Repurposes the Pause Menu's "Open to LAN" button as Burrow's "Host World"
 * / "Host Settings" control panel (keeping it accessible even while Burrow
 * has published the integrated server), and adds a full-width "Friends" button
 * cleanly inside the Pause Menu grid above Disconnect / Save and Quit.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin {
    @Shadow
    @Final
    private static Component SHARE_TO_LAN;

    @Redirect(
            method = "createPauseMenu",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/server/IntegratedServer;isPublished()Z"
            )
    )
    private boolean burrow$keepHostButtonVisible(IntegratedServer server) {
        if (HostSession.isHosting() || HostSession.hasWePublished()) {
            return false;
        }
        return server.isPublished();
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
                    target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;I)Lnet/minecraft/client/gui/layouts/LayoutElement;"
            )
    )
    private <T extends LayoutElement> T burrow$addFriendsBeforeDisconnect(
            GridLayout.RowHelper rowHelper,
            T disconnectBtn,
            int columns
    ) {
        rowHelper.addChild(
                Button.builder(
                                Component.literal("Friends"),
                                b -> Minecraft.getInstance().setScreen(new BurrowFriendsScreen((PauseScreen) (Object) this)))
                        .width(204)
                        .build(),
                2
        );
        return rowHelper.addChild(disconnectBtn, columns);
    }
}
