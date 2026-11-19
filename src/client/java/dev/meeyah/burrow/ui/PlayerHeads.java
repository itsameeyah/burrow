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

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached player-face drawing helper backed by Minecraft 1.21.1's
 * {@link PlayerFaceRenderer} and {@code SkinManager}.
 *
 * Resolves full {@link GameProfile} textures asynchronously once per player
 * UUID and falls back cleanly to {@link DefaultPlayerSkin} until ready.
 */
public final class PlayerHeads {
    private static final UUID NIL_UUID = new UUID(0L, 0L);
    private static final Map<String, GameProfile> PROFILES = new ConcurrentHashMap<>();
    private static final Set<String> FETCHING = ConcurrentHashMap.newKeySet();

    private PlayerHeads() {}

    public static void draw(GuiGraphics g, @Nullable UUID uuid, @Nullable String name, int x, int y, int size) {
        // Subtle 1px dark frame behind the head so light skins pop cleanly.
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xAA000000);

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }

        if (uuid != null) {
            ClientPacketListener conn = mc.getConnection();
            if (conn != null) {
                PlayerInfo info = conn.getPlayerInfo(uuid);
                if (info != null) {
                    PlayerFaceRenderer.draw(g, info.getSkin(), x, y, size);
                    return;
                }
            }
        }

        GameProfile profile = resolveProfile(mc, uuid, name);
        PlayerSkin skin = mc.getSkinManager().getInsecureSkin(profile);
        PlayerFaceRenderer.draw(g, skin, x, y, size);
    }

    private static GameProfile resolveProfile(Minecraft mc, @Nullable UUID uuid, @Nullable String name) {
        String key = uuid != null
                ? "u:" + uuid
                : (name != null && !name.isBlank() ? "n:" + name.toLowerCase() : "fallback");
        GameProfile profile = PROFILES.computeIfAbsent(key, k ->
                new GameProfile(uuid != null ? uuid : NIL_UUID, name != null && !name.isBlank() ? name : "Steve"));

        if (uuid != null && FETCHING.add(key)) {
            MinecraftSessionService sessionService = mc.getMinecraftSessionService();
            if (sessionService != null) {
                Util.backgroundExecutor().execute(() -> {
                    try {
                        ProfileResult result = sessionService.fetchProfile(uuid, false);
                        if (result != null && result.profile() != null) {
                            PROFILES.put(key, result.profile());
                        }
                    } catch (Throwable ignored) {
                    }
                });
            }
        }
        return profile;
    }
}
