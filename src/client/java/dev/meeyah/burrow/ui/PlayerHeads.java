package dev.meeyah.burrow.ui;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cached player-face drawing helper backed by Minecraft 1.21.5's
 * {@link PlayerFaceRenderer} and {@link net.minecraft.client.resources.SkinManager}.
 *
 * Resolves full {@link GameProfile} textures asynchronously once per Mojang UUID
 * so skin textures load without blocking rendering.
 */
public final class PlayerHeads {
    private static final Map<UUID, GameProfile> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "burrow-skin-lookup");
        t.setDaemon(true);
        return t;
    });

    private PlayerHeads() {}

    public static void draw(GuiGraphics g, @Nullable UUID uuid, @Nullable String name, int x, int y, int size) {
        // Subtle 1px dark frame behind the head so light skins pop cleanly.
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xAA000000);
        PlayerFaceRenderer.draw(g, resolveSkin(uuid, name), x, y, size);
    }

    private static PlayerSkin resolveSkin(@Nullable UUID uuid, @Nullable String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return uuid != null ? DefaultPlayerSkin.get(uuid) : DefaultPlayerSkin.getDefaultSkin();
        }
        if (uuid != null) {
            ClientPacketListener conn = mc.getConnection();
            if (conn != null) {
                PlayerInfo info = conn.getPlayerInfo(uuid);
                if (info != null) {
                    return info.getSkin();
                }
            }
            GameProfile existing = CACHE.get(uuid);
            if (existing == null) {
                GameProfile placeholder = new GameProfile(uuid, (name != null && !name.isBlank()) ? name : "Steve");
                if (CACHE.putIfAbsent(uuid, placeholder) == null && uuid.version() == 4) {
                    MinecraftSessionService sessionService = mc.getMinecraftSessionService();
                    if (sessionService != null) {
                        EXECUTOR.execute(() -> {
                            try {
                                ProfileResult result = sessionService.fetchProfile(uuid, false);
                                if (result != null && result.profile() != null) {
                                    CACHE.put(uuid, result.profile());
                                }
                            } catch (Throwable ignored) {
                            }
                        });
                    }
                }
                existing = placeholder;
            }
            return mc.getSkinManager().getInsecureSkin(existing);
        }
        return DefaultPlayerSkin.getDefaultSkin();
    }
}
