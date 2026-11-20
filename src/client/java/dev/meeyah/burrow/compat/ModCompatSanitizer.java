package dev.meeyah.burrow.compat;

import com.mojang.datafixers.util.Pair;
import dev.meeyah.burrow.mixin.DisplayAccessor;
import dev.meeyah.burrow.mixin.EntityAccessor;
import dev.meeyah.burrow.mixin.EntityEventPacketAccessor;
import dev.meeyah.burrow.mixin.ItemDisplayAccessor;
import dev.meeyah.burrow.mixin.SectionBlocksUpdatePacketAccessor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-connection outbound packet sanitizer for players missing one or more
 * non-vanilla mod namespaces on a Burrow-hosted world.
 *
 * Replaces only missing-mod content with unmistakable vanilla placeholders
 * (zero custom registry entries in Burrow itself):
 * - Particles & sounds from missing mods -> silently dropped
 * - Menus from missing mods -> cancelled with an in-game chat notice
 * - Items from missing mods -> missing-texture 3D cube (ITEM_MODEL = burrow:missing)
 *   keeping original display name + "Missing mod: <modid>" lore
 * - Entities from missing mods -> minecraft:item_display holding the missing-texture
 *   cube scaled to the entity's exact (bbWidth, bbHeight, bbWidth) + floating label
 * - Blocks from missing mods -> solid placeholder (magenta concrete) or air for
 *   non-solid blocks so movement doesn't rubberband
 */
public final class ModCompatSanitizer {
    private static final Logger LOG = LoggerFactory.getLogger("Burrow");
    /** Non-existent model ID renders Minecraft's native purple-and-black missing cube. */
    private static final Identifier MISSING_MODEL = Identifier.fromNamespaceAndPath("burrow", "missing");

    private ModCompatSanitizer() {}

    /**
     * Returns:
     * - the original {@code packet} reference when no sanitization is needed
     * - a replacement {@link Packet} when rewritten
     * - {@code null} when the packet should be dropped
     */
    public static @Nullable Packet<?> sanitize(ServerGamePacketListenerImpl listener, Packet<?> packet) {
        ServerPlayer player = listener.player;
        if (player == null || packet == null) {
            return packet;
        }
        ModCompat.CompatSession session = ModCompat.sessionFor(player.getUUID());
        if (session == null) {
            return packet;
        }
        try {
            return sanitizePacket(player, session, packet);
        } catch (Throwable t) {
            LOG.warn("Burrow mod-compat sanitizer error for {}: {}", packet.getClass().getSimpleName(), t.toString());
            return packet;
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Packet<?> sanitizePacket(
            ServerPlayer player,
            ModCompat.CompatSession session,
            Packet<?> packet
    ) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            return sanitizeBundle(player, session, bundle);
        }
        if (packet instanceof ClientboundLevelParticlesPacket pkt) {
            return isMissingParticle(pkt.getParticle(), session) ? null : pkt;
        }
        if (packet instanceof ClientboundSoundPacket pkt) {
            Identifier loc = pkt.getSound().value().location();
            return session.isMissing(loc.getNamespace()) ? null : pkt;
        }
        if (packet instanceof ClientboundSoundEntityPacket pkt) {
            Identifier loc = pkt.getSound().value().location();
            return session.isMissing(loc.getNamespace()) ? null : pkt;
        }
        if (packet instanceof ClientboundOpenScreenPacket pkt) {
            Identifier menuId = BuiltInRegistries.MENU.getKey(pkt.getType());
            if (menuId != null && session.isMissing(menuId.getNamespace())) {
                // Keep server container state in sync so client doesn't desync clicks.
                if (player.containerMenu != player.inventoryMenu) {
                    player.containerMenu = player.inventoryMenu;
                }
                player.sendSystemMessage(Component.literal(
                        "[Burrow] Can't open that menu — missing mod: " + menuId.getNamespace()
                ).withColor(0xFFFFCC66));
                return null;
            }
            return pkt;
        }
        if (packet instanceof ClientboundBlockEntityDataPacket pkt) {
            return session.isMissingBlockEntity(pkt.getType()) ? null : pkt;
        }
        if (packet instanceof ClientboundAddEntityPacket pkt) {
            return sanitizeAddEntity(player, session, pkt);
        }
        if (packet instanceof ClientboundSetEntityDataPacket pkt) {
            return sanitizeEntityData(session, pkt);
        }
        if (packet instanceof ClientboundUpdateAttributesPacket pkt) {
            return session.isSpoofedEntity(pkt.getEntityId()) ? null : pkt;
        }
        if (packet instanceof ClientboundAnimatePacket pkt) {
            return session.isSpoofedEntity(pkt.getId()) ? null : pkt;
        }
        if (packet instanceof ClientboundEntityEventPacket pkt) {
            int entityId = ((EntityEventPacketAccessor) pkt).burrow$entityId();
            return session.isSpoofedEntity(entityId) ? null : pkt;
        }
        if (packet instanceof ClientboundRemoveEntitiesPacket pkt) {
            for (int id : pkt.getEntityIds()) {
                session.unmarkSpoofedEntity(id);
            }
            return pkt;
        }
        if (packet instanceof ClientboundSetEquipmentPacket pkt) {
            if (session.isSpoofedEntity(pkt.getEntity())) {
                return null;
            }
            return sanitizeEquipment(session, pkt);
        }
        if (packet instanceof ClientboundContainerSetSlotPacket pkt) {
            ItemStack clean = sanitizeItemStack(pkt.getItem(), session);
            return clean == pkt.getItem()
                    ? pkt
                    : new ClientboundContainerSetSlotPacket(pkt.getContainerId(), pkt.getStateId(), pkt.getSlot(), clean);
        }
        if (packet instanceof ClientboundContainerSetContentPacket pkt) {
            return sanitizeContainerContent(session, pkt);
        }
        if (packet instanceof ClientboundSetPlayerInventoryPacket pkt) {
            ItemStack clean = sanitizeItemStack(pkt.contents(), session);
            return clean == pkt.contents()
                    ? pkt
                    : new ClientboundSetPlayerInventoryPacket(pkt.slot(), clean);
        }
        if (packet instanceof ClientboundSetCursorItemPacket pkt) {
            ItemStack clean = sanitizeItemStack(pkt.contents(), session);
            return clean == pkt.contents()
                    ? pkt
                    : new ClientboundSetCursorItemPacket(clean);
        }
        if (packet instanceof ClientboundBlockUpdatePacket pkt) {
            BlockState clean = sanitizeBlockState(pkt.getBlockState(), session);
            return clean == pkt.getBlockState()
                    ? pkt
                    : new ClientboundBlockUpdatePacket(pkt.getPos(), clean);
        }
        if (packet instanceof ClientboundSectionBlocksUpdatePacket pkt) {
            return sanitizeSectionBlocksUpdate(session, pkt);
        }
        if (packet instanceof ClientboundLevelChunkWithLightPacket pkt) {
            return sanitizeChunk(player, session, pkt);
        }
        return packet;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Packet<?> sanitizeBundle(
            ServerPlayer player,
            ModCompat.CompatSession session,
            ClientboundBundlePacket bundle
    ) {
        boolean changed = false;
        List<Packet<? super ClientGamePacketListener>> out = new ArrayList<>();
        for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
            Packet<?> clean = sanitizePacket(player, session, sub);
            if (clean != sub) {
                changed = true;
            }
            if (clean == null) {
                continue;
            }
            if (clean instanceof ClientboundBundlePacket nested) {
                for (Packet<? super ClientGamePacketListener> inner : nested.subPackets()) {
                    out.add(inner);
                }
            } else {
                out.add((Packet<? super ClientGamePacketListener>) clean);
            }
        }
        if (!changed) {
            return bundle;
        }
        if (out.isEmpty()) {
            return null;
        }
        return new ClientboundBundlePacket(out);
    }

    private static boolean isMissingParticle(@Nullable ParticleOptions particle, ModCompat.CompatSession session) {
        if (particle == null) return false;
        Identifier typeId = BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getType());
        if (typeId != null && session.isMissing(typeId.getNamespace())) {
            return true;
        }
        if (particle instanceof BlockParticleOption blockOpt && session.isMissingBlock(blockOpt.getState())) {
            return true;
        }
        if (particle instanceof ItemParticleOption itemOpt) {
            Identifier itemId = BuiltInRegistries.ITEM.getKey(itemOpt.getItem().getItem());
            return itemId != null && session.isMissing(itemId.getNamespace());
        }
        return false;
    }

    private static Packet<?> sanitizeAddEntity(
            ServerPlayer player,
            ModCompat.CompatSession session,
            ClientboundAddEntityPacket pkt
    ) {
        Identifier typeId = BuiltInRegistries.ENTITY_TYPE.getKey(pkt.getType());
        if (typeId == null || !session.isMissing(typeId.getNamespace())) {
            return pkt;
        }

        Entity real = player.level().getEntity(pkt.getId());
        float width = real != null ? Math.clamp(real.getBbWidth(), 0.25f, 16.0f) : 0.8f;
        float height = real != null ? Math.clamp(real.getBbHeight(), 0.25f, 16.0f) : 1.0f;
        String displayName = real != null ? real.getDisplayName().getString() : typeId.getPath();

        session.markSpoofedEntity(pkt.getId());

        EntityType<?> itemDisplayType = BuiltInRegistries.ENTITY_TYPE.getValue(
                Identifier.withDefaultNamespace("item_display"));
        if (itemDisplayType == null) {
            return pkt;
        }

        ClientboundAddEntityPacket displaySpawn = new ClientboundAddEntityPacket(
                pkt.getId(),
                pkt.getUUID(),
                pkt.getX(),
                pkt.getY(),
                pkt.getZ(),
                0.0f,
                pkt.getYRot(),
                itemDisplayType,
                0,
                pkt.getMovement(),
                pkt.getYHeadRot()
        );

        ItemStack missingCube = new ItemStack(Items.PAPER);
        missingCube.set(DataComponents.ITEM_MODEL, MISSING_MODEL);

        Component label = Component.literal(displayName + " [missing: " + typeId.getNamespace() + "]")
                .withColor(0xFFFF77FF);

        List<SynchedEntityData.DataValue<?>> meta = List.of(
                SynchedEntityData.DataValue.create(ItemDisplayAccessor.burrow$itemStackId(), missingCube),
                SynchedEntityData.DataValue.create(DisplayAccessor.burrow$translationId(), new Vector3f(0.0f, height * 0.5f, 0.0f)),
                SynchedEntityData.DataValue.create(DisplayAccessor.burrow$scaleId(), new Vector3f(width, height, width)),
                SynchedEntityData.DataValue.create(EntityAccessor.burrow$customNameId(), Optional.of(label)),
                SynchedEntityData.DataValue.create(EntityAccessor.burrow$customNameVisibleId(), Boolean.TRUE)
        );
        ClientboundSetEntityDataPacket displayMeta = new ClientboundSetEntityDataPacket(pkt.getId(), meta);

        return new ClientboundBundlePacket(List.of(displaySpawn, displayMeta));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static @Nullable Packet<?> sanitizeEntityData(
            ModCompat.CompatSession session,
            ClientboundSetEntityDataPacket pkt
    ) {
        if (session.isSpoofedEntity(pkt.id())) {
            return null;
        }
        List<SynchedEntityData.DataValue<?>> items = pkt.packedItems();
        boolean changed = false;
        List<SynchedEntityData.DataValue<?>> out = null;

        for (int i = 0; i < items.size(); i++) {
            SynchedEntityData.DataValue<?> dv = items.get(i);
            Object val = dv.value();
            SynchedEntityData.DataValue<?> replacement = dv;
            boolean drop = false;

            if (val instanceof ItemStack stack) {
                ItemStack clean = sanitizeItemStack(stack, session);
                if (clean != stack) {
                    replacement = new SynchedEntityData.DataValue(dv.id(), dv.serializer(), clean);
                }
            } else if (val instanceof ParticleOptions particle && isMissingParticle(particle, session)) {
                drop = true;
            } else if (val instanceof BlockState bs && session.isMissingBlock(bs)) {
                replacement = new SynchedEntityData.DataValue(dv.id(), dv.serializer(), sanitizeBlockState(bs, session));
            } else if (val instanceof Optional<?> opt && opt.isPresent() && opt.get() instanceof BlockState bs && session.isMissingBlock(bs)) {
                replacement = new SynchedEntityData.DataValue(dv.id(), dv.serializer(), Optional.of(sanitizeBlockState(bs, session)));
            }

            if (drop || replacement != dv) {
                if (!changed) {
                    changed = true;
                    out = new ArrayList<>(items.size());
                    for (int j = 0; j < i; j++) {
                        out.add(items.get(j));
                    }
                }
                if (!drop) {
                    out.add(replacement);
                }
            } else if (changed) {
                out.add(dv);
            }
        }

        if (!changed) {
            return pkt;
        }
        if (out.isEmpty()) {
            return null;
        }
        return new ClientboundSetEntityDataPacket(pkt.id(), out);
    }

    private static Packet<?> sanitizeEquipment(
            ModCompat.CompatSession session,
            ClientboundSetEquipmentPacket pkt
    ) {
        List<Pair<EquipmentSlot, ItemStack>> slots = pkt.getSlots();
        boolean changed = false;
        List<Pair<EquipmentSlot, ItemStack>> out = null;
        for (int i = 0; i < slots.size(); i++) {
            Pair<EquipmentSlot, ItemStack> pair = slots.get(i);
            ItemStack clean = sanitizeItemStack(pair.getSecond(), session);
            if (clean != pair.getSecond()) {
                if (!changed) {
                    changed = true;
                    out = new ArrayList<>(slots.size());
                    for (int j = 0; j < i; j++) {
                        out.add(slots.get(j));
                    }
                }
                out.add(Pair.of(pair.getFirst(), clean));
            } else if (changed) {
                out.add(pair);
            }
        }
        return changed ? new ClientboundSetEquipmentPacket(pkt.getEntity(), out) : pkt;
    }

    private static Packet<?> sanitizeContainerContent(
            ModCompat.CompatSession session,
            ClientboundContainerSetContentPacket pkt
    ) {
        List<ItemStack> items = pkt.items();
        boolean changed = false;
        List<ItemStack> out = null;
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            ItemStack clean = sanitizeItemStack(s, session);
            if (clean != s) {
                if (!changed) {
                    changed = true;
                    out = new ArrayList<>(items.size());
                    for (int j = 0; j < i; j++) {
                        out.add(items.get(j));
                    }
                }
                out.add(clean);
            } else if (changed) {
                out.add(s);
            }
        }
        ItemStack carried = sanitizeItemStack(pkt.carriedItem(), session);
        if (!changed && carried == pkt.carriedItem()) {
            return pkt;
        }
        return new ClientboundContainerSetContentPacket(
                pkt.containerId(),
                pkt.stateId(),
                changed ? out : items,
                carried
        );
    }

    private static ItemStack sanitizeItemStack(@Nullable ItemStack stack, ModCompat.CompatSession session) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId != null && session.isMissing(itemId.getNamespace())) {
            ItemStack placeholder = new ItemStack(Items.PAPER, stack.getCount());
            placeholder.set(DataComponents.ITEM_MODEL, MISSING_MODEL);
            placeholder.set(DataComponents.CUSTOM_NAME, stack.getHoverName().copy()
                    .withStyle(style -> style.withItalic(false).withColor(0xFFFF77FF)));
            placeholder.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("Missing mod: " + itemId.getNamespace())
                            .withStyle(style -> style.withItalic(false).withColor(0xFFAAAAAA))
            )));
            return placeholder;
        }

        // Vanilla (or shared modded) item carrying modded DataComponents from a missing mod:
        // strip only the missing-namespace components so ItemStack.STREAM_CODEC never fails.
        DataComponentPatch patch = stack.getComponentsPatch();
        if (!patch.isEmpty()) {
            List<DataComponentType<?>> toStrip = null;
            for (Map.Entry<DataComponentType<?>, Optional<?>> entry : patch.entrySet()) {
                Identifier compId = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey());
                if (compId != null && session.isMissing(compId.getNamespace())) {
                    if (toStrip == null) toStrip = new ArrayList<>();
                    toStrip.add(entry.getKey());
                }
            }
            if (toStrip != null) {
                ItemStack copy = stack.copy();
                for (DataComponentType<?> type : toStrip) {
                    copy.remove(type);
                }
                return copy;
            }
        }
        return stack;
    }

    private static BlockState sanitizeBlockState(BlockState state, ModCompat.CompatSession session) {
        if (!session.isMissingBlock(state)) {
            return state;
        }
        boolean solid = state.blocksMotion()
                || !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        if (!solid) {
            return Blocks.AIR.defaultBlockState();
        }
        Block fallback = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace("magenta_concrete"));
        return (fallback != null ? fallback : Blocks.STONE).defaultBlockState();
    }

    private static Packet<?> sanitizeSectionBlocksUpdate(
            ModCompat.CompatSession session,
            ClientboundSectionBlocksUpdatePacket pkt
    ) {
        SectionBlocksUpdatePacketAccessor acc = (SectionBlocksUpdatePacketAccessor) pkt;
        BlockState[] states = acc.burrow$states();
        boolean anyMissing = false;
        for (BlockState s : states) {
            if (session.isMissingBlock(s)) {
                anyMissing = true;
                break;
            }
        }
        if (!anyMissing) {
            return pkt;
        }
        SectionPos sectionPos = acc.burrow$sectionPos();
        short[] positions = acc.burrow$positions();
        ByteBuf raw = Unpooled.buffer();
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(raw);
            SectionPos.STREAM_CODEC.encode(buf, sectionPos);
            buf.writeVarInt(positions.length);
            for (int i = 0; i < positions.length; i++) {
                BlockState clean = sanitizeBlockState(states[i], session);
                buf.writeVarLong((long) Block.getId(clean) << 12 | positions[i]);
            }
            return ClientboundSectionBlocksUpdatePacket.STREAM_CODEC.decode(buf);
        } finally {
            raw.release();
        }
    }

    private static Packet<?> sanitizeChunk(
            ServerPlayer player,
            ModCompat.CompatSession session,
            ClientboundLevelChunkWithLightPacket pkt
    ) {
        LevelChunk chunk = player.level().getChunkSource().getChunkNow(pkt.getX(), pkt.getZ());
        if (chunk == null) {
            return pkt;
        }

        // Ultra-fast check: scans only small section palettes + block entities.
        boolean hasMissingBlocks = false;
        for (LevelChunkSection section : chunk.getSections()) {
            if (section.maybeHas(session::isMissingBlock)) {
                hasMissingBlocks = true;
                break;
            }
        }
        boolean hasMissingBe = false;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (session.isMissingBlockEntity(be.getType())) {
                hasMissingBe = true;
                break;
            }
        }
        if (!hasMissingBlocks && !hasMissingBe) {
            return pkt;
        }

        ByteBuf sectionRaw = Unpooled.buffer();
        ByteBuf packetRaw = Unpooled.buffer();
        try {
            FriendlyByteBuf sectionBuf = new FriendlyByteBuf(sectionRaw);
            for (LevelChunkSection section : chunk.getSections()) {
                if (!section.maybeHas(session::isMissingBlock)) {
                    section.write(sectionBuf);
                } else {
                    PalettedContainer<BlockState> copy = section.getStates().copy();
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState s = copy.get(x, y, z);
                                if (session.isMissingBlock(s)) {
                                    copy.set(x, y, z, sanitizeBlockState(s, session));
                                }
                            }
                        }
                    }
                    new LevelChunkSection(copy, section.getBiomes()).write(sectionBuf);
                }
            }

            byte[] sectionBytes = new byte[sectionRaw.readableBytes()];
            sectionRaw.readBytes(sectionBytes);

            List<BlockEntity> keptBes = new ArrayList<>();
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (!session.isMissingBlockEntity(be.getType())) {
                    keptBes.add(be);
                }
            }

            RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(packetRaw, player.registryAccess());
            out.writeInt(pkt.getX());
            out.writeInt(pkt.getZ());

            // Heightmaps
            Map<Heightmap.Types, long[]> heightmaps = pkt.getChunkData().getHeightmaps();
            out.writeVarInt(heightmaps.size());
            for (Map.Entry<Heightmap.Types, long[]> entry : heightmaps.entrySet()) {
                Heightmap.Types.STREAM_CODEC.encode(out, entry.getKey());
                ByteBufCodecs.LONG_ARRAY.encode(out, entry.getValue());
            }

            // Sanitized chunk sections buffer
            out.writeVarInt(sectionBytes.length);
            out.writeBytes(sectionBytes);

            // Filtered block entities
            out.writeVarInt(keptBes.size());
            for (BlockEntity be : keptBes) {
                BlockPos pos = be.getBlockPos();
                int xz = SectionPos.sectionRelative(pos.getX()) << 4 | SectionPos.sectionRelative(pos.getZ());
                out.writeByte(xz);
                out.writeShort(pos.getY());
                ByteBufCodecs.registry(Registries.BLOCK_ENTITY_TYPE).encode(out, be.getType());
                CompoundTag tag = be.getUpdateTag(player.registryAccess());
                out.writeNbt(tag == null || tag.isEmpty() ? null : tag);
            }

            // Light data (unchanged)
            pkt.getLightData().write(out);

            return ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(out);
        } finally {
            sectionRaw.release();
            packetRaw.release();
        }
    }
}
