package dev.meeyah.burrow.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.client.gui.font.providers.GlyphProviderDefinition;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Loads Minecraft's built-in vanilla default font directly from
 * {@link VanillaPackResources} so Burrow's dark-slate modal UI always renders
 * crisp, untinted vanilla white text even when a third-party resource pack
 * replaces or recolors {@code minecraft:default} (e.g. pink/styled fonts).
 *
 * Resource packs that explicitly target Burrow can override this by providing
 * {@code assets/burrow/font/default.json}, in which case Burrow falls back to
 * Minecraft's active font.
 */
public final class BurrowVanillaFont {
    private static final Logger LOG = LoggerFactory.getLogger("burrow");
    private static final Identifier VANILLA_DEFAULT_FONT = Identifier.withDefaultNamespace("default");
    private static final Identifier BURROW_FONT_OVERRIDE = Identifier.fromNamespaceAndPath("burrow", "font/default.json");

    private static volatile @Nullable Font cachedFont = null;
    private static volatile boolean attemptedLoad = false;

    private BurrowVanillaFont() {}

    public static Font get() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            throw new IllegalStateException("Minecraft instance unavailable");
        }
        try {
            if (mc.getResourceManager() != null
                    && mc.getResourceManager().getResource(BURROW_FONT_OVERRIDE).isPresent()) {
                return mc.font;
            }
        } catch (Throwable ignored) {
        }

        Font existing = cachedFont;
        if (existing != null) {
            return existing;
        }
        if (attemptedLoad) {
            return mc.font;
        }
        attemptedLoad = true;

        try {
            Font built = buildVanillaFont(mc);
            if (built != null) {
                cachedFont = built;
                return built;
            }
        } catch (Throwable t) {
            LOG.warn("Failed to load built-in vanilla font for Burrow UI; falling back to active font", t);
        }
        return mc.font;
    }

    private static @Nullable Font buildVanillaFont(Minecraft mc) {
        VanillaPackResources vanilla = mc.getVanillaPackResources();
        if (vanilla == null) {
            return null;
        }

        // Intentional: do NOT call close() on this manager because that would close VanillaPackResources.
        ResourceManager vanillaRm = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(vanilla.fullResources()));

        List<GlyphProvider.Conditional> loadedProviders = new ArrayList<>();
        Set<Identifier> visited = new HashSet<>();
        loadFontJson(vanillaRm, VANILLA_DEFAULT_FONT, FontOption.Filter.ALWAYS_PASS, visited, loadedProviders);

        if (loadedProviders.isEmpty()) {
            return null;
        }

        GlyphStitcher stitcher = new GlyphStitcher(
                mc.getTextureManager(),
                Identifier.fromNamespaceAndPath("burrow", "vanilla_font"));
        FontSet fontSet = new FontSet(stitcher);
        fontSet.reload(loadedProviders, Set.of());

        return new Font(new Font.Provider() {
            @Override
            public GlyphSource glyphs(FontDescription description) {
                return fontSet.source(false);
            }

            @Override
            public EffectGlyph effect() {
                return fontSet.whiteGlyph();
            }
        });
    }

    private static void loadFontJson(
            ResourceManager rm,
            Identifier fontId,
            FontOption.Filter inheritedFilter,
            Set<Identifier> visited,
            List<GlyphProvider.Conditional> out
    ) {
        if (!visited.add(fontId)) {
            return;
        }
        Identifier resId = Identifier.fromNamespaceAndPath(
                fontId.getNamespace(),
                "font/" + fontId.getPath() + ".json");
        Optional<Resource> resOpt = rm.getResource(resId);
        if (resOpt.isEmpty()) {
            return;
        }

        JsonElement providersEl;
        try (Reader reader = resOpt.get().openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            providersEl = root.get("providers");
        } catch (Exception e) {
            LOG.warn("Could not read vanilla font JSON {}", resId, e);
            return;
        }
        if (providersEl == null || !providersEl.isJsonArray()) {
            return;
        }

        var parsed = GlyphProviderDefinition.Conditional.CODEC.listOf()
                .parse(JsonOps.INSTANCE, providersEl)
                .result();
        if (parsed.isEmpty()) {
            return;
        }

        for (GlyphProviderDefinition.Conditional cond : parsed.get()) {
            FontOption.Filter merged = inheritedFilter.merge(cond.filter());
            cond.definition().unpack().ifLeft(loader -> {
                try {
                    GlyphProvider provider = loader.load(rm);
                    out.add(new GlyphProvider.Conditional(provider, merged));
                } catch (Exception e) {
                    LOG.debug("Skipping vanilla glyph provider in {}: {}", resId, e.getMessage());
                }
            }).ifRight(ref -> loadFontJson(rm, ref.id(), merged, visited, out));
        }
    }
}
