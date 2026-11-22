package dev.meeyah.burrow.ui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads Burrow's GUI sprites with explicit {@code burrow:} resource-pack
 * override support, falling back to Minecraft's built-in vanilla pack textures
 * (via {@link VanillaPackResources}) so unrelated resource packs never clash
 * with Burrow's dark-slate modal UI.
 *
 * Resource pack authors who want to theme Burrow explicitly can provide any of:
 * - {@code assets/burrow/textures/gui/sprites/panel_bg.png} (+ .mcmeta)
 * - {@code assets/burrow/textures/gui/sprites/tab.png}
 * - {@code assets/burrow/textures/gui/sprites/tab_selected.png}
 * - {@code assets/burrow/textures/gui/sprites/tab_highlighted.png}
 * - {@code assets/burrow/textures/gui/sprites/tab_selected_highlighted.png}
 * - {@code assets/burrow/textures/gui/sprites/button.png}
 * - {@code assets/burrow/textures/gui/sprites/button_highlighted.png}
 */
public final class BurrowVanillaSprites {
    public record SpriteSpec(
            String burrowName,
            Identifier burrowSpriteId,
            Identifier burrowTexId,
            Identifier vanillaTexId,
            Identifier vanillaSpriteFallback,
            Identifier dynamicTexId,
            int texW,
            int texH,
            int border
    ) {}

    public static final SpriteSpec PANEL_BG = spec("panel_bg", "friends/background", 236, 34, 8);
    public static final SpriteSpec TAB_IDLE = spec("tab", "widget/tab", 130, 24, 2);
    public static final SpriteSpec TAB_SELECTED = spec("tab_selected", "widget/tab_selected", 130, 24, 2);
    public static final SpriteSpec TAB_HOVER = spec("tab_highlighted", "widget/tab_highlighted", 130, 24, 2);
    public static final SpriteSpec TAB_SELECTED_HOVER = spec("tab_selected_highlighted", "widget/tab_selected_highlighted", 130, 24, 2);
    public static final SpriteSpec BUTTON_IDLE = spec("button", "widget/button", 200, 20, 3);
    public static final SpriteSpec BUTTON_HOVER = spec("button_highlighted", "widget/button_highlighted", 200, 20, 3);
    public static final SpriteSpec BUTTON_DISABLED = spec("button_disabled", "widget/button_disabled", 200, 20, 2);
    public static final SpriteSpec SCROLLER = spec("scroller", "widget/scroller", 6, 32, 1);
    public static final SpriteSpec SCROLLER_BG = spec("scroller_background", "widget/scroller_background", 6, 32, 1);
    public static final SpriteSpec SLIDER = spec("slider", "widget/slider", 200, 20, 2);
    public static final SpriteSpec SLIDER_HOVER = spec("slider_highlighted", "widget/slider_highlighted", 200, 20, 2);
    public static final SpriteSpec SLIDER_HANDLE = spec("slider_handle", "widget/slider_handle", 8, 20, 2);
    public static final SpriteSpec SLIDER_HANDLE_HOVER = spec("slider_handle_highlighted", "widget/slider_handle_highlighted", 8, 20, 2);
    public static final SpriteSpec TEXT_FIELD = spec("text_field", "widget/text_field", 200, 20, 1);
    public static final SpriteSpec TEXT_FIELD_HOVER = spec("text_field_highlighted", "widget/text_field_highlighted", 200, 20, 1);

    private static final Map<String, Boolean> VANILLA_LOADED = new HashMap<>();
    private static final Map<String, Boolean> CUSTOM_OVERRIDE_CACHE = new HashMap<>();
    private static @Nullable List<String> cachedPackIds = null;

    private BurrowVanillaSprites() {}

    private static SpriteSpec spec(String burrowName, String vanillaSprite, int texW, int texH, int border) {
        return new SpriteSpec(
                burrowName,
                Identifier.fromNamespaceAndPath("burrow", burrowName),
                Identifier.fromNamespaceAndPath("burrow", "textures/gui/sprites/" + burrowName + ".png"),
                Identifier.withDefaultNamespace("textures/gui/sprites/" + vanillaSprite + ".png"),
                Identifier.withDefaultNamespace(vanillaSprite),
                Identifier.fromNamespaceAndPath("burrow", "vanilla_gui/" + burrowName),
                texW,
                texH,
                border
        );
    }

    /**
     * Draw {@code spec}: uses an explicit {@code burrow:<name>} resource pack
     * sprite if present, otherwise renders Minecraft's built-in vanilla texture
     * directly so unrelated resource packs never alter Burrow's panels/tabs.
     */
    public static void draw(GuiGraphicsExtractor g, SpriteSpec spec, int x, int y, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        if (hasCustomBurrowOverride(mc, spec)) {
            g.blitSprite(RenderPipelines.GUI_TEXTURED, spec.burrowSpriteId(), x, y, w, h);
            return;
        }
        if (ensureVanillaLoaded(mc, spec)) {
            drawNineSlice(g, spec.dynamicTexId(), x, y, w, h, spec.texW(), spec.texH(), spec.border());
            return;
        }
        g.blitSprite(RenderPipelines.GUI_TEXTURED, spec.vanillaSpriteFallback(), x, y, w, h);
    }

    private static boolean hasCustomBurrowOverride(Minecraft mc, SpriteSpec spec) {
        if (mc.getResourcePackRepository() == null || mc.getResourceManager() == null) {
            return false;
        }
        Collection<String> selected = mc.getResourcePackRepository().getSelectedIds();
        if (cachedPackIds == null || !sameIds(cachedPackIds, selected)) {
            cachedPackIds = List.copyOf(selected);
            CUSTOM_OVERRIDE_CACHE.clear();
        }
        return CUSTOM_OVERRIDE_CACHE.computeIfAbsent(spec.burrowName(), k -> {
            ResourceManager rm = mc.getResourceManager();
            return rm.getResource(spec.burrowTexId()).isPresent();
        });
    }

    private static boolean ensureVanillaLoaded(Minecraft mc, SpriteSpec spec) {
        Boolean loaded = VANILLA_LOADED.get(spec.burrowName());
        if (loaded != null) {
            return loaded;
        }
        try {
            VanillaPackResources vanilla = mc.getVanillaPackResources();
            if (vanilla == null) {
                VANILLA_LOADED.put(spec.burrowName(), false);
                return false;
            }
            IoSupplier<InputStream> supplier = vanilla.getResource(PackType.CLIENT_RESOURCES, spec.vanillaTexId());
            if (supplier == null) {
                VANILLA_LOADED.put(spec.burrowName(), false);
                return false;
            }
            try (InputStream in = supplier.get()) {
                NativeImage image = NativeImage.read(in);
                DynamicTexture tex = new DynamicTexture(() -> "burrow_vanilla_" + spec.burrowName(), image);
                mc.getTextureManager().register(spec.dynamicTexId(), tex);
                VANILLA_LOADED.put(spec.burrowName(), true);
                return true;
            }
        } catch (Exception e) {
            VANILLA_LOADED.put(spec.burrowName(), false);
            return false;
        }
    }

    private static void drawNineSlice(
            GuiGraphicsExtractor g,
            Identifier tex,
            int x, int y, int w, int h,
            int tw, int th, int b
    ) {
        int innerW = Math.max(0, w - b * 2);
        int innerH = Math.max(0, h - b * 2);
        int srcInnerW = Math.max(1, tw - b * 2);
        int srcInnerH = Math.max(1, th - b * 2);

        // Corners
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0.0f, 0.0f, b, b, b, b, tw, th);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x + w - b, y, tw - b, 0.0f, b, b, b, b, tw, th);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y + h - b, 0.0f, th - b, b, b, b, b, tw, th);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x + w - b, y + h - b, tw - b, th - b, b, b, b, b, tw, th);

        // Edges
        if (innerW > 0) {
            g.blit(RenderPipelines.GUI_TEXTURED, tex, x + b, y, b, 0.0f, innerW, b, srcInnerW, b, tw, th);
            g.blit(RenderPipelines.GUI_TEXTURED, tex, x + b, y + h - b, b, th - b, innerW, b, srcInnerW, b, tw, th);
        }
        if (innerH > 0) {
            g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y + b, 0.0f, b, b, innerH, b, srcInnerH, tw, th);
            g.blit(RenderPipelines.GUI_TEXTURED, tex, x + w - b, y + b, tw - b, b, b, innerH, b, srcInnerH, tw, th);
        }

        // Center
        if (innerW > 0 && innerH > 0) {
            g.blit(RenderPipelines.GUI_TEXTURED, tex, x + b, y + b, b, b, innerW, innerH, srcInnerW, srcInnerH, tw, th);
        }
    }

    private static boolean sameIds(List<String> a, Collection<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        int i = 0;
        for (String s : b) {
            if (!a.get(i++).equals(s)) {
                return false;
            }
        }
        return true;
    }
}
