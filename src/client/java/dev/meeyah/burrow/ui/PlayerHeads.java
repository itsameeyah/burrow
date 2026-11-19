package dev.meeyah.burrow.ui;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached player-face drawing helper backed by Minecraft 1.21.4's
 * {@link PlayerFaceRenderer} and {@code SkinManager}.
 *
 * Resolves online-mode UUID profiles asynchronously via Mojang's session
 * service (once per UUID) and falls back to live server tab-list skins or
 * default Steve/Alex skins until resolved.
 */
public final class PlayerHeads {
    private static final Map<UUID, GameProfile> RESOLVED_PROFILES = new ConcurrentHashMap<>();
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private PlayerHeads() {}

    public static PlayerSkin skin(@Nullable UUID uuid, @Nullable String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            ClientPacketListener conn = mc.getConnection();
            if (conn != null) {
                PlayerInfo info = uuid != null ? conn.getPlayerInfo(uuid) : null;
                if (info == null && name != null && !name.isBlank()) {
                    info = conn.getPlayerInfo(name);
                }
                if (info != null) {
                    return info.getSkin();
                }
            }
            if (uuid != null) {
                GameProfile resolved = RESOLVED_PROFILES.get(uuid);
                if (resolved != null) {
                    return mc.getSkinManager().getInsecureSkin(resolved);
                }
                if (uuid.version() == 4 && IN_FLIGHT.add(uuid)) {
                    resolveAsync(mc, uuid, name);
                }
                return DefaultPlayerSkin.get(uuid);
            }
        }
        UUID fallback = (name != null && !name.isBlank())
                ? UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8))
                : new UUID(0L, 0L);
        return DefaultPlayerSkin.get(fallback);
    }

    private static void resolveAsync(Minecraft mc, UUID uuid, @Nullable String name) {
        Util.backgroundExecutor().execute(() -> {
            try {
                MinecraftSessionService service = mc.getMinecraftSessionService();
                if (service == null) {
                    return;
                }
                ProfileResult result = service.fetchProfile(uuid, false);
                if (result != null && result.profile() != null) {
                    RESOLVED_PROFILES.put(uuid, result.profile());
                    mc.execute(() -> mc.getSkinManager().getOrLoad(result.profile()));
                } else {
                    RESOLVED_PROFILES.put(uuid, new GameProfile(uuid, name == null ? "" : name));
                }
            } catch (Throwable ignored) {
                RESOLVED_PROFILES.put(uuid, new GameProfile(uuid, name == null ? "" : name));
            }
        });
    }

    public static void draw(GuiGraphics g, @Nullable UUID uuid, @Nullable String name, int x, int y, int size) {
        // Subtle 1px dark frame behind the head so light skins pop cleanly.
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xAA000000);
        PlayerFaceRenderer.draw(g, skin(uuid, name), x, y, size);
    }
}
