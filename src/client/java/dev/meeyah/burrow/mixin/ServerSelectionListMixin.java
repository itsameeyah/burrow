package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.ui.BurrowMultiplayerWorlds;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Appends joinable Burrow worlds (Invited, Friends, Friends of Friends) and the
 * "Join by Burrow Code" input + live preview card to the very bottom of
 * Minecraft's vanilla Multiplayer server list.
 */
@Mixin(ServerSelectionList.class)
public abstract class ServerSelectionListMixin implements BurrowMultiplayerWorlds.BurrowServerListAccess {
    @Unique
    private ServerSelectionList.Entry burrow$header;

    @Unique
    private List<ServerSelectionList.Entry> burrow$entries;

    @Unique
    private ServerSelectionList.Entry burrow$codeHeader;

    @Unique
    private BurrowMultiplayerWorlds.CodeInputEntry burrow$codeInput;

    @Unique
    private ServerSelectionList.Entry burrow$codePreview;

    @Shadow
    private void refreshEntries() {}

    @Unique
    private ServerSelectionList.Entry burrow$getHeader() {
        if (this.burrow$header == null) {
            this.burrow$header = new BurrowMultiplayerWorlds.HeaderEntry();
        }
        return this.burrow$header;
    }

    @Unique
    private ServerSelectionList.Entry burrow$getCodeHeader() {
        if (this.burrow$codeHeader == null) {
            this.burrow$codeHeader = new BurrowMultiplayerWorlds.CodeHeaderEntry();
        }
        return this.burrow$codeHeader;
    }

    @Unique
    private BurrowMultiplayerWorlds.CodeInputEntry burrow$getCodeInput() {
        if (this.burrow$codeInput == null) {
            this.burrow$codeInput = new BurrowMultiplayerWorlds.CodeInputEntry();
        }
        return this.burrow$codeInput;
    }

    @ModifyArg(
            method = "refreshEntries",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/multiplayer/ServerSelectionList;replaceEntries(Ljava/util/Collection;)V"
            ),
            index = 0
    )
    private Collection<ServerSelectionList.Entry> burrow$appendBurrowWorlds(Collection<ServerSelectionList.Entry> entries) {
        List<ServerSelectionList.Entry> list = (entries instanceof List<ServerSelectionList.Entry> l)
                ? l
                : new ArrayList<>(entries);

        if (this.burrow$entries != null && !this.burrow$entries.isEmpty()) {
            list.add(burrow$getHeader());
            list.addAll(this.burrow$entries);
        }

        list.add(burrow$getCodeHeader());
        list.add(burrow$getCodeInput());
        if (this.burrow$codePreview != null) {
            list.add(this.burrow$codePreview);
        }
        return list;
    }

    @Override
    public void burrow$refreshBurrowWorlds() {
        boolean hadPreview = this.burrow$codePreview != null;
        boolean boxFocused = this.burrow$codeInput != null && this.burrow$codeInput.isBoxFocused();
        this.burrow$entries = BurrowMultiplayerWorlds.buildEntries();
        this.burrow$codePreview = BurrowMultiplayerWorlds.buildCodePreviewEntry();
        this.refreshEntries();
        if (boxFocused && this.burrow$codeInput != null) {
            ((net.minecraft.client.gui.components.AbstractContainerWidget) (Object) this).setFocused(this.burrow$codeInput);
            this.burrow$codeInput.focusBox();
        }
        if (!hadPreview && this.burrow$codePreview != null) {
            ((net.minecraft.client.gui.components.AbstractSelectionList<?>) (Object) this)
                    .setScrollAmount(((net.minecraft.client.gui.components.AbstractSelectionList<?>) (Object) this).maxScrollAmount());
        }
    }

    @Override
    public boolean burrow$handleCodeKeyPressed(KeyEvent event) {
        if (this.burrow$codeInput != null && this.burrow$codeInput.isBoxFocused()) {
            return this.burrow$codeInput.keyPressed(event);
        }
        return false;
    }
}
