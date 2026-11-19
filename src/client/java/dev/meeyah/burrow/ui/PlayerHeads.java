package dev.meeyah.burrow.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached player-face drawing helper backed by Minecraft 1.21.11's
 * {@link PlayerFaceRenderer} and {@link PlayerSkinRenderCache}.
 *
 * Caching {@link ResolvableProfile} per player avoids per-frame allocations
 * and lets vanilla's skin cache resolve real skins asynchronously once.
 */
public final class PlayerHeads {
    private static final Map<String, ResolvableProfile> CACHE = new ConcurrentHashMap<>();

    private PlayerHeads() {}

    public static ResolvableProfile profile(@Nullable UUID uuid, @Nullable String name) {
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

    public static void draw(GuiGraphics g, @Nullable UUID uuid, @Nullable String name, int x, int y, int size) {
        // Subtle 1px dark frame behind the head so light skins pop cleanly.
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xAA000000);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        PlayerSkinRenderCache cache = mc.playerSkinRenderCache();
        if (cache == null) {
            return;
        }
        PlayerSkin skin = cache.getOrDefault(profile(uuid, name)).playerSkin();
        PlayerFaceRenderer.draw(g, skin, x, y, size);
    }
}
