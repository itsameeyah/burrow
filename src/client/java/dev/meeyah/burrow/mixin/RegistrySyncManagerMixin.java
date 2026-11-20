package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.session.HostWorldState;
import net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.players.NameAndId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Host-side companion for Burrow's "Mismatched Mods: Allowed / Strict" toggle.
 *
 * When a singleplayer host has client-side visual mods installed (e.g.
 * Particular, Falling Leaves, Visuality), those mods register extra particle
 * or menu entries in the integrated server JVM that Fabric's registry sync
 * would otherwise force every joining friend to install. When "Mismatched Mods"
 * is Allowed (the default), we skip strict registry sync so friends can join
 * seamlessly; toggling it to Strict restores Fabric's normal enforcement.
 */
@Mixin(value = RegistrySyncManager.class, remap = false)
public abstract class RegistrySyncManagerMixin {
    @Unique
    private static final Logger BURROW_LOG = LoggerFactory.getLogger("Burrow");

    @Inject(method = "configureClient", at = @At("HEAD"), cancellable = true)
    private static void burrow$allowMismatchedClientsWhenEnabled(
            ServerConfigurationPacketListenerImpl handler,
            MinecraftServer server,
            CallbackInfo ci
    ) {
        if (!HostSession.isHosting() || !(server instanceof IntegratedServer)) {
            return;
        }
        if (server.isSingleplayerOwner(new NameAndId(handler.getOwner()))) {
            return;
        }
        HostWorldState state = HostSession.worldState(server);
        if (state != null && state.allowMismatchedMods()) {
            BURROW_LOG.info("Skipping strict Fabric registry sync for {} (Mismatched Mods: Allowed)",
                    handler.getOwner().name());
            ci.cancel();
        }
    }
}
