package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.ui.BurrowFriendsScreen;
import net.minecraft.client.Minecraft;
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
 * Repurposes the Pause Menu's "Open to LAN" slot as Burrow's "Host World" /
 * "Host Settings" panel and adds a full-width "Friends" button inside the
 * Pause Menu grid right above the Return to Menu / Disconnect button.
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

    @Redirect(
            method = "createPauseMenu",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;I)Lnet/minecraft/client/gui/layouts/LayoutElement;"
            )
    )
    private LayoutElement burrow$addFriendsButton(
            GridLayout.RowHelper rowHelper,
            LayoutElement disconnectButton,
            int columns
    ) {
        Screen self = (Screen) (Object) this;
        Button friendsBtn = Button.builder(
                Component.literal("Friends"),
                b -> Minecraft.getInstance().setScreen(new BurrowFriendsScreen(self))
        ).width(204).build();
        rowHelper.addChild(friendsBtn, 2);
        return rowHelper.addChild(disconnectButton, columns);
    }
}
