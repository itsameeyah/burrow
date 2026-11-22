package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.state.FriendsState;
import dev.meeyah.burrow.ui.BurrowMultiplayerWorlds;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the Burrow Worlds + Join-by-Code section at the bottom of the
 * Multiplayer screen synced in real time and guards selection / keyboard input.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin {
    @Shadow
    protected ServerSelectionList serverSelectionList;

    @Shadow
    private Button selectButton;

    @Shadow
    protected abstract void onSelectedChange();

    @Unique
    private FriendsState.Listener burrow$listener;

    @Unique
    private int burrow$pruneTick = 0;

    @Unique
    private FriendsState.Listener burrow$getListener() {
        if (this.burrow$listener == null) {
            this.burrow$listener = this::burrow$onFriendsChanged;
        }
        return this.burrow$listener;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void burrow$initBurrowWorlds(CallbackInfo ci) {
        FriendsState.Listener listener = burrow$getListener();
        BurrowClient.friends().removeListener(listener);
        BurrowClient.friends().addListener(listener);
        burrow$onFriendsChanged();
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void burrow$cleanupBurrowWorlds(CallbackInfo ci) {
        if (this.burrow$listener != null) {
            BurrowClient.friends().removeListener(this.burrow$listener);
        }
        BurrowClient.friends().clearCodePreview();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void burrow$tickPruneInvites(CallbackInfo ci) {
        if (++this.burrow$pruneTick >= 20) {
            this.burrow$pruneTick = 0;
            BurrowClient.friends().pruneExpired();
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void burrow$routeCodeInputKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (this.serverSelectionList instanceof BurrowMultiplayerWorlds.BurrowServerListAccess access
                && access.burrow$handleCodeKeyPressed(event)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "onSelectedChange", at = @At("TAIL"))
    private void burrow$guardSelection(CallbackInfo ci) {
        if (this.serverSelectionList == null || this.selectButton == null) {
            return;
        }
        ServerSelectionList.Entry selected = this.serverSelectionList.getSelected();
        if (selected instanceof BurrowMultiplayerWorlds.HeaderEntry
                || selected instanceof BurrowMultiplayerWorlds.CodeHeaderEntry
                || selected instanceof BurrowMultiplayerWorlds.CodeInputEntry) {
            this.selectButton.active = false;
        } else if (selected instanceof BurrowMultiplayerWorlds.WorldEntry we && !we.world().canJoin()) {
            this.selectButton.active = false;
        } else if (selected instanceof BurrowMultiplayerWorlds.CodePreviewEntry cpe && !cpe.canJoin()) {
            this.selectButton.active = false;
        }
    }

    @Unique
    private void burrow$onFriendsChanged() {
        if (this.serverSelectionList instanceof BurrowMultiplayerWorlds.BurrowServerListAccess access) {
            access.burrow$refreshBurrowWorlds();
            this.onSelectedChange();
        }
    }
}
