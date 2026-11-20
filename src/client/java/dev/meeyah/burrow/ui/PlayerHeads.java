package dev.meeyah.burrow.ui;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.item.component.ResolvableProfile;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached player-face drawing helper backed by Minecraft 26.2's
 * {@link PlayerFaceExtractor} and {@code PlayerSkinRenderCache}.
 *
 * Reuses already-resolved {@link GameProfile}s from the active server or local
 * client first (zero Mojang HTTP requests), and otherwise resolves by online
 * UUID (v4) before falling back to username so we avoid hitting Mojang's
 * strictly rate-limited {@code api.mojang.com} name-lookup endpoint.
 */
public final class PlayerHeads {
    private static final Map<String, ResolvableProfile> CACHE = new ConcurrentHashMap<>();

    private PlayerHeads() {}

    public static ResolvableProfile profile(@Nullable UUID uuid, @Nullable String name) {
        if (uuid != null) {
            // Fast path: reuse live GameProfile with signed skin textures if the
            // player is on our current server or is the local player (0 HTTP calls).
            GameProfile live = findLiveProfile(uuid);
            if (live != null) {
                return CACHE.computeIfAbsent("r:" + uuid, k -> ResolvableProfile.createResolved(live));
            }
            // Prefer online UUID (v4) over username to skip api.mojang.com name lookup
            // and cut Mojang rate-limit pressure in half.
            if (uuid.version() == 4) {
                return CACHE.computeIfAbsent("u:" + uuid,
                        k -> ResolvableProfile.createUnresolved(uuid));
            }
        }
        if (name != null && !name.isBlank()) {
            return CACHE.computeIfAbsent("n:" + name.toLowerCase(),
                    k -> ResolvableProfile.createUnresolved(name));
        }
        if (uuid != null) {
            return CACHE.computeIfAbsent("u:" + uuid,
                    k -> ResolvableProfile.createUnresolved(uuid));
        }
        return CACHE.computeIfAbsent("fallback",
                k -> ResolvableProfile.createUnresolved("Steve"));
    }

    private static @Nullable GameProfile findLiveProfile(UUID uuid) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return null;
        }
        ClientPacketListener conn = mc.getConnection();
        if (conn != null) {
            PlayerInfo info = conn.getPlayerInfo(uuid);
            if (info != null) {
                GameProfile gp = info.getProfile();
                if (gp != null && !gp.properties().isEmpty()) {
                    return gp;
                }
            }
        }
        GameProfile self = mc.getGameProfile();
        if (self != null && uuid.equals(self.id()) && !self.properties().isEmpty()) {
            return self;
        }
        return null;
    }

    public static void draw(GuiGraphicsExtractor g, @Nullable UUID uuid, @Nullable String name, int x, int y, int size) {
        // Subtle 1px dark frame behind the head so light skins pop cleanly.
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xAA000000);
        PlayerFaceExtractor.extractRenderState(g, profile(uuid, name), x, y, size);
    }
}
