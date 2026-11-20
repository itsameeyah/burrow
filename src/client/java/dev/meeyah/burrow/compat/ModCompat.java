package dev.meeyah.burrow.compat;

import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.session.HostWorldState;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Host-side graceful mod-mismatch compatibility manager.
 *
 * During Configuration on a Burrow-hosted integrated server, exchanges a tiny
 * namespace list with each remote joiner so the host knows the exact set of
 * non-vanilla mod namespaces the joiner is missing:
 * - Matching friends (missing set empty): zero packet rewriting overhead.
 * - Mismatched friends: only packets referencing their missing namespaces are
 *   translated by {@link ModCompatSanitizer} into safe vanilla placeholders.
 * - Older Burrow clients (no compat channel): safely treats all non-vanilla
 *   host namespaces as missing when "Mismatched Mods: Allowed" is enabled.
 */
public final class ModCompat {
    private static final Logger LOG = LoggerFactory.getLogger("Burrow");
    private static final int MAX_NAMESPACES = 256;

    private static volatile boolean registered = false;
    private static final Map<UUID, CompatSession> SESSIONS = new ConcurrentHashMap<>();

    private ModCompat() {}

    /** Per-player compatibility state tracked on the host. */
    public static final class CompatSession {
        private final Set<String> missingNamespaces;
        private final Set<Integer> spoofedEntities = ConcurrentHashMap.newKeySet();

        public CompatSession(Set<String> missingNamespaces) {
            this.missingNamespaces = Set.copyOf(missingNamespaces);
        }

        public boolean isMissing(@Nullable String namespace) {
            return namespace != null && !"minecraft".equals(namespace) && this.missingNamespaces.contains(namespace);
        }

        public boolean isMissingBlock(BlockState state) {
            if (state == null) return false;
            Block block = state.getBlock();
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            return id != null && isMissing(id.getNamespace());
        }

        public boolean isMissingBlockEntity(@Nullable BlockEntityType<?> type) {
            if (type == null) return false;
            Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
            return id != null && isMissing(id.getNamespace());
        }

        public void markSpoofedEntity(int entityId) {
            this.spoofedEntities.add(entityId);
        }

        public void unmarkSpoofedEntity(int entityId) {
            this.spoofedEntities.remove(entityId);
        }

        public boolean isSpoofedEntity(int entityId) {
            return this.spoofedEntities.contains(entityId);
        }

        public Set<String> missingNamespaces() {
            return this.missingNamespaces;
        }
    }

    /** Host -> Joiner configuration payload listing host's non-vanilla namespaces. */
    public record CompatCheckPayload(List<String> namespaces) implements CustomPacketPayload {
        public static final Type<CompatCheckPayload> ID =
                new Type<>(Identifier.fromNamespaceAndPath("burrow", "compat_check"));
        public static final StreamCodec<FriendlyByteBuf, CompatCheckPayload> CODEC =
                StreamCodec.of(
                        (buf, p) -> writeStringList(buf, p.namespaces),
                        buf -> new CompatCheckPayload(readStringList(buf))
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    /** Joiner -> Host configuration reply listing namespaces missing locally. */
    public record CompatReplyPayload(List<String> missingNamespaces) implements CustomPacketPayload {
        public static final Type<CompatReplyPayload> ID =
                new Type<>(Identifier.fromNamespaceAndPath("burrow", "compat_reply"));
        public static final StreamCodec<FriendlyByteBuf, CompatReplyPayload> CODEC =
                StreamCodec.of(
                        (buf, p) -> writeStringList(buf, p.missingNamespaces),
                        buf -> new CompatReplyPayload(readStringList(buf))
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    private record CompatCheckTask(List<String> hostNamespaces) implements ConfigurationTask {
        static final Type TYPE = new Type("burrow:compat_check");

        @Override
        public void start(Consumer<Packet<?>> sender) {
            sender.accept(ServerConfigurationNetworking.createS2CPacket(
                    new CompatCheckPayload(this.hostNamespaces)));
        }

        @Override
        public Type type() {
            return TYPE;
        }
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        PayloadTypeRegistry.configurationS2C().register(CompatCheckPayload.ID, CompatCheckPayload.CODEC);
        PayloadTypeRegistry.configurationC2S().register(CompatReplyPayload.ID, CompatReplyPayload.CODEC);

        // Joiner side: compute which of the host's non-vanilla namespaces we lack.
        ClientConfigurationNetworking.registerGlobalReceiver(CompatCheckPayload.ID, (payload, context) -> {
            Set<String> local = collectModdedNamespaces();
            List<String> missing = new ArrayList<>();
            for (String ns : payload.namespaces()) {
                if (!"minecraft".equals(ns) && !local.contains(ns)) {
                    missing.add(ns);
                }
            }
            context.responseSender().sendPacket(new CompatReplyPayload(missing));
        });

        // Host side: record per-player missing namespaces when joiner replies.
        ServerConfigurationNetworking.registerGlobalReceiver(CompatReplyPayload.ID, (payload, context) -> {
            UUID playerUuid = context.networkHandler().getOwner().id();
            if (payload.missingNamespaces().isEmpty()) {
                SESSIONS.remove(playerUuid);
            } else {
                Set<String> missing = new LinkedHashSet<>(payload.missingNamespaces());
                SESSIONS.put(playerUuid, new CompatSession(missing));
                LOG.info("Enabled Burrow mod-compat fallback for {} (missing: {})",
                        context.networkHandler().getOwner().name(),
                        String.join(", ", missing));
            }
            context.networkHandler().completeTask(CompatCheckTask.TYPE);
        });

        // Host side: run compat check during Configuration on Burrow-hosted worlds.
        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            if (!HostSession.isHosting() || !(server instanceof IntegratedServer)) {
                return;
            }
            if (server.isSingleplayerOwner(new NameAndId(handler.getOwner()))) {
                return;
            }
            UUID playerUuid = handler.getOwner().id();
            Set<String> hostMods = collectModdedNamespaces();
            if (hostMods.isEmpty()) {
                SESSIONS.remove(playerUuid);
                return;
            }
            if (ServerConfigurationNetworking.canSend(handler, CompatCheckPayload.ID)) {
                handler.addTask(new CompatCheckTask(new ArrayList<>(hostMods)));
            } else {
                // Joiner is on an older Burrow build without compat_check:
                // safely treat all host non-vanilla namespaces as missing when allowed.
                HostWorldState state = HostSession.worldState(server);
                if (state != null && state.allowMismatchedMods()) {
                    SESSIONS.put(playerUuid, new CompatSession(hostMods));
                    LOG.info("Enabled Burrow mod-compat fallback for legacy client {} (sanitizing: {})",
                            handler.getOwner().name(),
                            String.join(", ", hostMods));
                }
            }
        });

        ServerConfigurationConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler.getOwner() != null && handler.getOwner().id() != null) {
                SESSIONS.remove(handler.getOwner().id());
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler.player != null) {
                SESSIONS.remove(handler.player.getUUID());
            }
        });
    }

    public static @Nullable CompatSession sessionFor(@Nullable UUID playerUuid) {
        return playerUuid == null ? null : SESSIONS.get(playerUuid);
    }

    public static void clearAllSessions() {
        SESSIONS.clear();
    }

    /** Collects all non-vanilla namespaces across synced gameplay registries. */
    public static Set<String> collectModdedNamespaces() {
        Set<String> out = new LinkedHashSet<>();
        addNamespaces(BuiltInRegistries.BLOCK, out);
        addNamespaces(BuiltInRegistries.ITEM, out);
        addNamespaces(BuiltInRegistries.ENTITY_TYPE, out);
        addNamespaces(BuiltInRegistries.PARTICLE_TYPE, out);
        addNamespaces(BuiltInRegistries.SOUND_EVENT, out);
        addNamespaces(BuiltInRegistries.MENU, out);
        addNamespaces(BuiltInRegistries.BLOCK_ENTITY_TYPE, out);
        addNamespaces(BuiltInRegistries.DATA_COMPONENT_TYPE, out);
        return out;
    }

    private static void addNamespaces(Registry<?> registry, Set<String> out) {
        for (Identifier id : registry.keySet()) {
            String ns = id.getNamespace();
            if (!"minecraft".equals(ns)) {
                out.add(ns);
            }
        }
    }

    private static void writeStringList(FriendlyByteBuf buf, List<String> list) {
        int count = Math.min(list.size(), MAX_NAMESPACES);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeUtf(list.get(i), 64);
        }
    }

    private static List<String> readStringList(FriendlyByteBuf buf) {
        int count = Math.min(Math.max(0, buf.readVarInt()), MAX_NAMESPACES);
        if (count == 0) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(buf.readUtf(64));
        }
        return out;
    }
}
