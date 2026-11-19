package dev.meeyah.burrow.session;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.TrapDoorBlock;

import java.util.UUID;

/**
 * Bedrock-inspired per-world roles (Visitor / Member / Operator), join gates
 * (ban / lock / player cap), and server-side Visitor enforcement on the host's
 * integrated server.
 */
public final class HostPermissions {
    private static volatile boolean registered = false;

    private HostPermissions() {}

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        // Enforce bans, locks, player cap, and apply role when a guest finishes joining.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (server.isSingleplayerOwner(player.nameAndId())) {
                return;
            }
            HostWorldState state = HostSession.worldState(server);
            if (state == null) {
                return;
            }
            UUID uuid = player.getUUID();
            if (state.isBanned(uuid)) {
                handler.disconnect(Component.literal("You are banned from this world."));
                return;
            }
            if (HostSession.isHosting() && state.locked()) {
                handler.disconnect(Component.literal("This world is currently locked."));
                return;
            }
            if (HostSession.isHosting() && server.getPlayerCount() > state.maxPlayers()) {
                handler.disconnect(Component.literal("This world is full."));
                return;
            }
            HostWorldState.Role role = state.roleOf(uuid);
            applyRoleOnServerThread(server, player, role, false);
            HostSession.syncStateWithCount(server.getPlayerCount());
        });

        // Clean up runtime join metadata and update player count on leave.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (server.isSingleplayerOwner(player.nameAndId())) {
                return;
            }
            HostWorldState state = HostSession.worldState(server);
            if (state != null) {
                state.clearJoinInfo(player.getUUID());
            }
            int remaining = Math.max(1, server.getPlayerCount() - 1);
            HostSession.syncStateWithCount(remaining);
        });

        // Visitor enforcement: block breaking, placing, containers, entity interactions, attacking.
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, blockState, blockEntity) ->
                !isVisitor(player));

        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                isVisitor(player) ? InteractionResult.FAIL : InteractionResult.PASS);

        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!isVisitor(player)) {
                return InteractionResult.PASS;
            }
            Block block = level.getBlockState(hitResult.getBlockPos()).getBlock();
            if (isVisitorAllowedBlock(block)) {
                return InteractionResult.PASS;
            }
            return InteractionResult.FAIL;
        });

        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
                isVisitor(player) ? InteractionResult.FAIL : InteractionResult.PASS);

        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
                isVisitor(player) ? InteractionResult.FAIL : InteractionResult.PASS);
    }

    /** Doors, buttons, levers, gates, bells, and note blocks stay usable for Visitors. */
    private static boolean isVisitorAllowedBlock(Block block) {
        return block instanceof DoorBlock
                || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock
                || block instanceof ButtonBlock
                || block instanceof LeverBlock
                || block instanceof BellBlock
                || block instanceof NoteBlock;
    }

    /** True iff this player is a non-host Visitor on our running integrated server. */
    public static boolean isVisitor(Player player) {
        if (player == null || player.level().isClientSide() || !(player instanceof ServerPlayer sp)) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc == null ? null : mc.getSingleplayerServer();
        if (server == null || server.isSingleplayerOwner(sp.nameAndId())) {
            return false;
        }
        HostWorldState state = HostSession.worldState(server);
        return state != null && state.roleOf(sp.getUUID()) == HostWorldState.Role.VISITOR;
    }

    /** Change a player's role (persisted + applied immediately if online). */
    public static void setPlayerRole(IntegratedServer server, UUID uuid, HostWorldState.Role role) {
        HostWorldState state = HostSession.worldState(server);
        if (state == null) {
            return;
        }
        state.setRole(uuid, role);
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null && !server.isSingleplayerOwner(player.nameAndId())) {
                applyRoleOnServerThread(server, player, role, true);
            }
        });
        HostSession.syncState();
    }

    /** Kick a player from the hosted world and close their relay tunnel. */
    public static void kickPlayer(IntegratedServer server, UUID uuid, String reason) {
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null && !server.isSingleplayerOwner(player.nameAndId())) {
                player.connection.disconnect(Component.literal(reason));
            }
        });
        HostSession.closeTunnelForPlayer(uuid);
    }

    /** Ban a player from this world (persisted), kick immediately, and sync ban list to Burrow. */
    public static void banPlayer(IntegratedServer server, UUID uuid, String name) {
        HostWorldState state = HostSession.worldState(server);
        if (state == null) {
            return;
        }
        state.ban(uuid, name);
        kickPlayer(server, uuid, "You have been banned from this world.");
        HostSession.syncState();
    }

    /** Remove a player from this world's ban list and sync to Burrow. */
    public static void unbanPlayer(IntegratedServer server, UUID uuid) {
        HostWorldState state = HostSession.worldState(server);
        if (state == null) {
            return;
        }
        state.unban(uuid);
        HostSession.syncState();
    }

    private static void applyRoleOnServerThread(
            MinecraftServer server,
            ServerPlayer player,
            HostWorldState.Role role,
            boolean notify
    ) {
        switch (role) {
            case VISITOR -> {
                if (server.getPlayerList().isOp(player.nameAndId())) {
                    server.getPlayerList().deop(player.nameAndId());
                }
                player.closeContainer();
                if (player.gameMode() != GameType.ADVENTURE) {
                    player.setGameMode(GameType.ADVENTURE);
                }
            }
            case MEMBER -> {
                if (server.getPlayerList().isOp(player.nameAndId())) {
                    server.getPlayerList().deop(player.nameAndId());
                }
                GameType worldDefault = server.getDefaultGameType();
                if (player.gameMode() == GameType.ADVENTURE && worldDefault != GameType.ADVENTURE) {
                    player.setGameMode(worldDefault);
                }
            }
            case OPERATOR -> {
                if (!server.getPlayerList().isOp(player.nameAndId())) {
                    server.getPlayerList().op(player.nameAndId());
                }
                GameType worldDefault = server.getDefaultGameType();
                if (player.gameMode() == GameType.ADVENTURE && worldDefault != GameType.ADVENTURE) {
                    player.setGameMode(worldDefault);
                }
            }
        }
        if (notify) {
            player.sendSystemMessage(Component.literal("Your world role is now: " + role.label()));
        }
    }
}
