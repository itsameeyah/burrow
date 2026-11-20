package dev.meeyah.burrow.mixin;

import dev.meeyah.burrow.session.JoinFlow;
import dev.meeyah.burrow.ui.BurrowToasts;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.fabric.api.event.registry.RegistryAttribute;
import net.fabricmc.fabric.impl.client.registry.sync.ClientRegistrySyncHandler;
import net.fabricmc.fabric.impl.registry.sync.packet.RegistrySyncPayload;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Joiner-side registry mismatch detector & one-shot "Join Anyway" filter for
 * Burrow worlds.
 *
 * - On normal (non-Burrow) servers: completely passive.
 * - On Burrow worlds (e.g. a host with Strict mod check enabled): detects any
 *   missing remote registries/entries and records them so
 *   {@link DisconnectedScreenMixin} can offer a "Join Anyway" button with a
 *   warning.
 * - When the user clicks "Join Anyway": strips ONLY the locally missing
 *   registries/entries from {@link RegistrySyncPayload}, keeping all shared
 *   vanilla and modded IDs synced cleanly.
 */
@Mixin(value = ClientRegistrySyncHandler.class, remap = false)
public abstract class ClientRegistrySyncHandlerMixin {
    @Unique
    private static final Logger BURROW_LOG = LoggerFactory.getLogger("Burrow");

    @ModifyVariable(method = "apply", at = @At("HEAD"), argsOnly = true)
    private static RegistrySyncPayload burrow$handleMismatchOnBurrowWorld(RegistrySyncPayload payload) {
        if (payload == null) {
            return null;
        }
        UUID hostUuid = JoinFlow.currentOrConnectingHostUuid();
        if (hostUuid == null) {
            return payload;
        }

        Map<Identifier, Object2IntMap<Identifier>> remoteMap = payload.registryMap();
        if (remoteMap == null || remoteMap.isEmpty()) {
            return payload;
        }

        Set<String> missingNamespaces = new LinkedHashSet<>();
        int missingCount = 0;

        for (Map.Entry<Identifier, Object2IntMap<Identifier>> regEntry : remoteMap.entrySet()) {
            Identifier regId = regEntry.getKey();
            Registry<?> localReg = BuiltInRegistries.REGISTRY.getValue(regId);
            if (localReg == null) {
                EnumSet<RegistryAttribute> attrs = payload.registryAttributes().get(regId);
                boolean optional = attrs != null && attrs.contains(RegistryAttribute.OPTIONAL);
                if (!optional) {
                    missingNamespaces.add(regId.getNamespace());
                    missingCount += Math.max(1, regEntry.getValue().size());
                }
                continue;
            }
            for (Identifier entryId : regEntry.getValue().keySet()) {
                if (!localReg.containsKey(entryId)) {
                    missingNamespaces.add(entryId.getNamespace());
                    missingCount++;
                }
            }
        }

        if (missingCount == 0) {
            return payload;
        }

        List<String> nsList = new ArrayList<>(missingNamespaces);
        if (!JoinFlow.consumeRegistryMismatchBypass(hostUuid)) {
            JoinFlow.recordRegistryMismatch(hostUuid, nsList, missingCount);
            return payload;
        }

        // User explicitly clicked "Join Anyway": strip only missing registries/entries
        // while keeping every locally known entry so vanilla & shared mod IDs stay synced.
        Map<Identifier, Object2IntMap<Identifier>> filteredMap = new LinkedHashMap<>();
        Map<Identifier, EnumSet<RegistryAttribute>> filteredAttrs = new LinkedHashMap<>();

        for (Map.Entry<Identifier, Object2IntMap<Identifier>> regEntry : remoteMap.entrySet()) {
            Identifier regId = regEntry.getKey();
            Registry<?> localReg = BuiltInRegistries.REGISTRY.getValue(regId);
            if (localReg == null) {
                continue;
            }
            Object2IntMap<Identifier> kept = new Object2IntLinkedOpenHashMap<>();
            for (Object2IntMap.Entry<Identifier> e : regEntry.getValue().object2IntEntrySet()) {
                if (localReg.containsKey(e.getKey())) {
                    kept.put(e.getKey(), e.getIntValue());
                }
            }
            if (!kept.isEmpty()) {
                filteredMap.put(regId, kept);
                EnumSet<RegistryAttribute> attrs = payload.registryAttributes().get(regId);
                if (attrs != null) {
                    filteredAttrs.put(regId, EnumSet.copyOf(attrs));
                }
            }
        }

        BURROW_LOG.warn("Ignoring {} unknown remote registry entries from Burrow host {} (namespaces: {})",
                missingCount, hostUuid, String.join(", ", nsList));
        BurrowToasts.showInfo("Mod Mismatch Ignored", "Missing host mods — some features may glitch.");
        return new RegistrySyncPayload(filteredMap, filteredAttrs);
    }
}
