package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.ui.BurrowMultiplayerWorlds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Appends joinable Burrow worlds (Invited, Friends, Friends of Friends, Open)
 * to the bottom of Minecraft's vanilla Multiplayer server list.
 */
@Mixin(ServerSelectionList.class)
public abstract class ServerSelectionListMixin extends ObjectSelectionList<ServerSelectionList.Entry>
        implements BurrowMultiplayerWorlds.BurrowServerListAccess {
    @Unique
    private final ServerSelectionList.Entry burrow$header = new BurrowMultiplayerWorlds.HeaderEntry();

    @Unique
    private List<ServerSelectionList.Entry> burrow$entries = List.of();

    protected ServerSelectionListMixin(Minecraft mc, int width, int height, int y, int itemHeight) {
        super(mc, width, height, y, itemHeight);
    }

    @Shadow
    private void refreshEntries() {}

    @Inject(method = "refreshEntries", at = @At("TAIL"))
    private void burrow$appendBurrowWorlds(CallbackInfo ci) {
        if (this.burrow$entries.isEmpty()) {
            return;
        }
        this.addEntry(this.burrow$header);
        for (ServerSelectionList.Entry entry : this.burrow$entries) {
            this.addEntry(entry);
        }
    }

    @Override
    public void burrow$refreshBurrowWorlds() {
        this.burrow$entries = BurrowMultiplayerWorlds.buildEntries((ServerSelectionList) (Object) this);
        this.refreshEntries();
    }
}
