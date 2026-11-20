package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.ui.BurrowMultiplayerWorlds;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Appends joinable Burrow worlds (Invited, Friends, Friends of Friends, Open)
 * to the bottom of Minecraft's vanilla Multiplayer server list.
 */
@Mixin(ServerSelectionList.class)
public abstract class ServerSelectionListMixin implements BurrowMultiplayerWorlds.BurrowServerListAccess {
    @Unique
    private ServerSelectionList.Entry burrow$header;

    @Unique
    private List<ServerSelectionList.Entry> burrow$entries;

    @Shadow
    private void refreshEntries() {}

    @Unique
    private ServerSelectionList.Entry burrow$getHeader() {
        if (this.burrow$header == null) {
            this.burrow$header = new BurrowMultiplayerWorlds.HeaderEntry();
        }
        return this.burrow$header;
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
        if (this.burrow$entries == null || this.burrow$entries.isEmpty()) {
            return entries;
        }
        ServerSelectionList.Entry header = burrow$getHeader();
        if (entries instanceof List<ServerSelectionList.Entry> list) {
            list.add(header);
            list.addAll(this.burrow$entries);
            return list;
        }
        List<ServerSelectionList.Entry> merged = new ArrayList<>(entries);
        merged.add(header);
        merged.addAll(this.burrow$entries);
        return merged;
    }

    @Override
    public void burrow$refreshBurrowWorlds() {
        this.burrow$entries = BurrowMultiplayerWorlds.buildEntries();
        this.refreshEntries();
    }
}
