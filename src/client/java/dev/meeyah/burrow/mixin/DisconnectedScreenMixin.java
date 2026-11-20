package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.session.JoinFlow;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When a Burrow join is rejected due to a Fabric registry mismatch (e.g. host
 * has Strict mod check enabled with extra client-side mods), adds a warning
 * and a "Join Anyway (Ignore Mismatch)" button to the disconnect screen.
 */
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {
    @Shadow
    @Final
    private DisconnectionDetails details;

    @Shadow
    @Final
    private LinearLayout layout;

    protected DisconnectedScreenMixin(Component title) {
        super(title);
    }

    @Inject(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/layouts/LinearLayout;arrangeElements()V"
            )
    )
    private void burrow$addJoinAnywayOnRegistryMismatch(CallbackInfo ci) {
        JoinFlow.RegistryMismatch mismatch = JoinFlow.peekRecentRegistryMismatch();
        if (mismatch == null) {
            return;
        }
        String reason = this.details != null && this.details.reason() != null
                ? this.details.reason().getString().toLowerCase()
                : "";
        if (!reason.contains("registry") && !reason.contains("mismatched mod")) {
            return;
        }

        Component warning = Component.literal(
                "Warning: Client-side mods are usually safe to ignore, but missing content mods may cause disconnects."
        ).withColor(0xFFFFCC66);
        this.layout.addChild(new MultiLineTextWidget(warning, this.font)
                .setMaxWidth(Math.max(200, this.width - 50))
                .setCentered(true));

        String nsPreview = mismatch.namespaces().isEmpty()
                ? mismatch.missingCount() + " entries"
                : String.join(", ", mismatch.namespaces());
        Button joinAnyway = Button.builder(
                        Component.literal("Join Anyway (Ignore Mismatch)").withColor(0xFFFFD27A),
                        btn -> JoinFlow.retryIgnoringRegistryMismatch(mismatch.hostUuid()))
                .width(200)
                .tooltip(Tooltip.create(Component.literal(
                        "Reconnect while ignoring unknown registry entries (" + nsPreview + ").")))
                .build();
        this.layout.addChild(joinAnyway);
    }
}
