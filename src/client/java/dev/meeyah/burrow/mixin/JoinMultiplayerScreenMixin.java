package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.state.FriendsState;
import dev.meeyah.burrow.ui.BurrowMultiplayerWorlds;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the Burrow Worlds section at the bottom of the Multiplayer screen
 * synced in real time, routes "Join Server" / Enter on a Burrow world row
 * to {@link BurrowMultiplayerWorlds.WorldEntry#join()}, and ensures the header
 * and incompatible worlds cannot be joined.
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
    private final FriendsState.Listener burrow$listener = this::burrow$onFriendsChanged;

    @Unique
    private int burrow$pruneTick = 0;

    @Inject(method = "init", at = @At("TAIL"))
    private void burrow$initBurrowWorlds(CallbackInfo ci) {
        BurrowClient.friends().removeListener(this.burrow$listener);
        BurrowClient.friends().addListener(this.burrow$listener);
        burrow$onFriendsChanged();
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void burrow$cleanupBurrowWorlds(CallbackInfo ci) {
        BurrowClient.friends().removeListener(this.burrow$listener);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void burrow$tickPruneInvites(CallbackInfo ci) {
        if (++this.burrow$pruneTick >= 20) {
            this.burrow$pruneTick = 0;
            BurrowClient.friends().pruneExpired();
        }
    }

    @Inject(method = "onSelectedChange", at = @At("TAIL"))
    private void burrow$guardSelection(CallbackInfo ci) {
        if (this.serverSelectionList == null || this.selectButton == null) {
            return;
        }
        ServerSelectionList.Entry selected = this.serverSelectionList.getSelected();
        if (selected instanceof BurrowMultiplayerWorlds.HeaderEntry) {
            this.selectButton.active = false;
        } else if (selected instanceof BurrowMultiplayerWorlds.WorldEntry we && we.world().versionMismatch()) {
            this.selectButton.active = false;
        }
    }

    @Inject(method = "joinSelectedServer", at = @At("HEAD"), cancellable = true)
    private void burrow$joinBurrowWorld(CallbackInfo ci) {
        if (this.serverSelectionList == null) {
            return;
        }
        ServerSelectionList.Entry selected = this.serverSelectionList.getSelected();
        if (selected instanceof BurrowMultiplayerWorlds.WorldEntry we) {
            we.join();
            ci.cancel();
        } else if (selected instanceof BurrowMultiplayerWorlds.HeaderEntry) {
            ci.cancel();
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
