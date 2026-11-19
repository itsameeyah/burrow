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
    private final ServerSelectionList.Entry burrow$header = new BurrowMultiplayerWorlds.HeaderEntry();

    @Unique
    private List<ServerSelectionList.Entry> burrow$entries = List.of();

    @Shadow
    private void refreshEntries() {}

    @ModifyArg(
            method = "refreshEntries",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/multiplayer/ServerSelectionList;replaceEntries(Ljava/util/Collection;)V"
            ),
            index = 0
    )
    private Collection<ServerSelectionList.Entry> burrow$appendBurrowWorlds(Collection<ServerSelectionList.Entry> entries) {
        if (this.burrow$entries.isEmpty()) {
            return entries;
        }
        if (entries instanceof List<ServerSelectionList.Entry> list) {
            list.add(this.burrow$header);
            list.addAll(this.burrow$entries);
            return list;
        }
        List<ServerSelectionList.Entry> merged = new ArrayList<>(entries);
        merged.add(this.burrow$header);
        merged.addAll(this.burrow$entries);
        return merged;
    }

    @Override
    public void burrow$refreshBurrowWorlds() {
        this.burrow$entries = BurrowMultiplayerWorlds.buildEntries();
        this.refreshEntries();
    }
}
