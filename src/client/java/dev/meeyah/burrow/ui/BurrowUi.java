package dev.meeyah.burrow.ui;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.state.UpdateChecker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

import org.jetbrains.annotations.Nullable;

/**
 * Shared vanilla-styled visual primitives for Burrow screens (Host World and
 * Friends) using Minecraft 26.2's built-in 9-slice panel, tab & button sprites.
 */
public final class BurrowUi {
    // Unified modal geometry so Friends, Requests, Host World, and Invite Friend
    // all share the exact same frame size and even 14px padding on every edge.
    public static final int PANEL_W = 360;
    public static final int PANEL_H = 237;
    public static final int PAD = 14;
    public static final int HEADER_TITLE_Y = 14;
    public static final int HEADER_SUB_Y = 26;
    public static final int TABS_Y = 41;
    public static final int TAB_H = 20;
    public static final int CONTENT_Y = 60;
    public static final int CONTENT_H = 136;
    public static final int FOOTER_Y = 203;

    public static final Identifier PANEL_BG = Identifier.withDefaultNamespace("friends/background");
    public static final Identifier TAB_IDLE = Identifier.withDefaultNamespace("widget/tab");
    public static final Identifier TAB_SELECTED = Identifier.withDefaultNamespace("widget/tab_selected");
    public static final Identifier TAB_HOVER = Identifier.withDefaultNamespace("widget/tab_highlighted");
    public static final Identifier TAB_SELECTED_HOVER = Identifier.withDefaultNamespace("widget/tab_selected_highlighted");
    public static final Identifier BUTTON_IDLE = Identifier.withDefaultNamespace("widget/button");
    public static final Identifier BUTTON_HOVER = Identifier.withDefaultNamespace("widget/button_highlighted");

    // Full-alpha ARGB palette tuned for vanilla dark panels.
    public static final int TEXT_PRIMARY = 0xFFFFFFFF;
    public static final int TEXT_SECONDARY = 0xFFB8B8C0;
    public static final int TEXT_MUTED = 0xFF888892;

    public static final int INSET_BG = 0xFF1A1A1E;
    public static final int INSET_BORDER = 0xFF0E0E12;
    public static final int CARD_BG = 0xFF232329;
    public static final int CARD_HOVER = 0xFF2C2C35;
    public static final int CARD_SELECTED = 0xFF30303B;
    public static final int CARD_BORDER = 0xFF141418;
    public static final int CARD_BORDER_ACTIVE = 0xFF4A4A5A;
    public static final int DRAWER_BG = 0xFF17171C;

    public static final int DOT_LIVE = 0xFF55EE77;
    public static final int DOT_LOCKED = 0xFFFFBB44;
    public static final int DOT_DND = 0xFFFF6B7A;
    public static final int DOT_NOTIFY = 0xFF55BBFF;
    public static final int DOT_OFFLINE = 0xFF70707A;

    public static final int STAR_FAVORITE = 0xFFFFD452;
    public static final int STAR_IDLE = 0xFF5A5A68;
    public static final int STAR_HOVER = 0xFFC8C8D6;

    public static final int ROLE_HOST = 0xFFFFD866;
    public static final int ROLE_OP = 0xFFFF88B8;
    public static final int ROLE_MEMBER = 0xFF7AE89B;
    public static final int ROLE_VISITOR = 0xFF7CC8FF;

    private BurrowUi() {}

    /** Built-in vanilla Minecraft font (ignores third-party resource-pack font recolors). */
    public static Font font() {
        return BurrowVanillaFont.get();
    }

    /** Outer vanilla 9-slice modal panel (uses explicit burrow:panel_bg override or built-in vanilla). */
    public static void drawModalPanel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        BurrowVanillaSprites.draw(g, BurrowVanillaSprites.PANEL_BG, x, y, w, h);
    }

    /** Crisp flat dark recessed container inside a modal panel. */
    public static void drawInsetBox(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, INSET_BG);
        g.outline(x, y, w, h, INSET_BORDER);
    }

    /** Small pill badge with leading status dot (aligned to rightX). */
    public static void drawStatusPill(
            GuiGraphicsExtractor g,
            Font font,
            int rightX,
            int y,
            int dotColor,
            String text
    ) {
        int textW = font.width(text);
        int pillW = textW + 18;
        int pillH = 16;
        int x = rightX - pillW;
        g.fill(x, y, x + pillW, y + pillH, 0xFF16161B);
        g.outline(x, y, pillW, pillH, 0xFF0B0B0E);
        g.fill(x + 6, y + 6, x + 10, y + 10, dotColor);
        g.text(font, text, x + 14, y + 4, TEXT_PRIMARY, false);
    }

    /** Compact colored tag pill at crisp native 1.0x pixel font scale with balanced padding. */
    public static int drawTagPill(GuiGraphicsExtractor g, Font font, int x, int y, String label, int color) {
        int textW = font.width(label);
        int w = textW + 6;
        int h = 11;
        int bg = (color & 0x00FFFFFF) | 0x28000000;
        int border = (color & 0x00FFFFFF) | 0x55000000;
        g.fill(x, y, x + w, y + h, bg);
        g.outline(x, y, w, h, border);
        // Native 1.0x glyph (7px tall) centered cleanly inside 11px height: 2px top, 2px bottom, 3px left/right
        g.text(font, label, x + 3, y + 2, color, false);
        return w;
    }

    private static final String[] STAR_FILLED = {
            "....H....",
            "...HHH...",
            "HHHHHHHHH",
            ".GGGGGGG.",
            "..GGGGG..",
            ".GGGGGGG.",
            ".GGG.GGG.",
            ".DD...DD."
    };

    private static final String[] STAR_OUTLINE = {
            "....#....",
            "...#.#...",
            "####.####",
            ".#.....#.",
            "..#...#..",
            ".#..#..#.",
            ".#.#.#.#.",
            ".##...##."
    };

    /** Crisp symmetric 9x8 pixel-art 5-point star icon (shaded gold when favorited, clean connected outline otherwise). */
    public static void drawStar(GuiGraphicsExtractor g, int x, int y, boolean favorited, boolean hovered) {
        if (favorited) {
            int hi = hovered ? 0xFFFFF0A8 : 0xFFFFE47A;
            int mid = hovered ? 0xFFFFD859 : 0xFFFFC83D;
            int dark = hovered ? 0xFFF0B22E : 0xFFE09F1F;
            for (int row = 0; row < STAR_FILLED.length; row++) {
                String line = STAR_FILLED[row];
                for (int col = 0; col < line.length(); col++) {
                    int argb = switch (line.charAt(col)) {
                        case 'H' -> hi;
                        case 'G' -> mid;
                        case 'D' -> dark;
                        default -> 0;
                    };
                    if (argb != 0) {
                        g.fill(x + col, y + row, x + col + 1, y + row + 1, argb);
                    }
                }
            }
        } else {
            int c = hovered ? STAR_HOVER : STAR_IDLE;
            for (int row = 0; row < STAR_OUTLINE.length; row++) {
                String line = STAR_OUTLINE[row];
                for (int col = 0; col < line.length(); col++) {
                    if (line.charAt(col) == '#') {
                        g.fill(x + col, y + row, x + col + 1, y + row + 1, c);
                    }
                }
            }
        }
    }

    /** Convenience builders for authentic vanilla-textured buttons immune to third-party resource packs. */
    public static VanillaButton button(String label, int x, int y, int w, int h, Runnable onPress) {
        return new VanillaButton(x, y, w, h, Component.literal(label), b -> onPress.run());
    }

    public static VanillaButton button(Component label, int x, int y, int w, int h, Runnable onPress) {
        return new VanillaButton(x, y, w, h, label, b -> onPress.run());
    }

    public static VanillaButton button(
            Component label,
            int x,
            int y,
            int w,
            int h,
            java.util.function.Consumer<VanillaButton> onPress
    ) {
        return new VanillaButton(x, y, w, h, label, onPress);
    }

    public static <T> VanillaCycleButton<T> cycleButton(
            int x,
            int y,
            int w,
            int h,
            T[] values,
            T initial,
            java.util.function.Function<T, Component> labeler,
            @Nullable java.util.function.Function<T, Component> tooltipper,
            java.util.function.Consumer<T> onChange
    ) {
        return new VanillaCycleButton<>(x, y, w, h, values, initial, labeler, tooltipper, onChange);
    }

    /**
     * Vanilla-styled button rendered via {@link BurrowVanillaSprites} +
     * {@link BurrowVanillaFont} so third-party resource packs never clash with
     * Burrow's dark-slate modal UI (still honors explicit {@code burrow:button} overrides).
     */
    public static class VanillaButton extends AbstractWidget {
        private final java.util.function.Consumer<VanillaButton> onPress;

        public VanillaButton(
                int x,
                int y,
                int w,
                int h,
                Component label,
                java.util.function.Consumer<VanillaButton> onPress
        ) {
            super(x, y, w, h, label);
            this.onPress = onPress;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            BurrowVanillaSprites.SpriteSpec sprite = !this.active
                    ? BurrowVanillaSprites.BUTTON_DISABLED
                    : (this.isHoveredOrFocused() ? BurrowVanillaSprites.BUTTON_HOVER : BurrowVanillaSprites.BUTTON_IDLE);
            BurrowVanillaSprites.draw(g, sprite, getX(), getY(), getWidth(), getHeight());

            Font font = font();
            Component msg = getMessage();
            int color;
            if (!this.active) {
                color = 0xFFA0A0A0;
            } else if (msg.getStyle().getColor() != null) {
                color = 0xFF000000 | msg.getStyle().getColor().getValue();
            } else {
                color = TEXT_PRIMARY;
            }
            int maxTextW = Math.max(8, getWidth() - 6);
            String raw = msg.getString();
            String display = font.width(raw) <= maxTextW
                    ? raw
                    : font.plainSubstrByWidth(raw, Math.max(4, maxTextW - font.width("..."))) + "...";
            int textY = getY() + (getHeight() - 8) / 2;
            g.centeredText(font, display, getX() + getWidth() / 2, textY, color);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (!this.active) {
                return;
            }
            playButtonClickSound(Minecraft.getInstance().getSoundManager());
            this.onPress.accept(this);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** Vanilla-textured cycle button immune to third-party resource-pack button/font overrides. */
    public static final class VanillaCycleButton<T> extends VanillaButton {
        private final T[] values;
        private int index;
        private final java.util.function.Function<T, Component> labeler;
        private final @Nullable java.util.function.Function<T, Component> tooltipper;
        private final java.util.function.Consumer<T> onChange;

        public VanillaCycleButton(
                int x,
                int y,
                int w,
                int h,
                T[] values,
                T initial,
                java.util.function.Function<T, Component> labeler,
                @Nullable java.util.function.Function<T, Component> tooltipper,
                java.util.function.Consumer<T> onChange
        ) {
            super(x, y, w, h, labeler.apply(initial), b -> {});
            this.values = values;
            this.index = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(initial)) {
                    this.index = i;
                    break;
                }
            }
            this.labeler = labeler;
            this.tooltipper = tooltipper;
            this.onChange = onChange;
            refreshLabelAndTooltip();
        }

        private void refreshLabelAndTooltip() {
            T val = this.values[this.index];
            setMessage(this.labeler.apply(val));
            if (this.tooltipper != null) {
                Component tip = this.tooltipper.apply(val);
                setTooltip(tip != null ? Tooltip.create(tip) : null);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (!this.active || !this.visible || !isMouseOver(event.x(), event.y())) {
                return false;
            }
            if (event.button() == 0 || event.button() == 1) {
                playButtonClickSound(Minecraft.getInstance().getSoundManager());
                int delta = (event.button() == 1 || (event.modifiers() & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0) ? -1 : 1;
                this.index = (this.index + delta + this.values.length) % this.values.length;
                refreshLabelAndTooltip();
                this.onChange.accept(this.values[this.index]);
                return true;
            }
            return false;
        }
    }

    /** Compact transparent 13x13 clickable star icon widget for favoriting friends. */
    public static final class StarButton extends AbstractWidget {
        private final boolean favorited;
        private final Runnable onToggle;

        public StarButton(int x, int y, boolean favorited, Runnable onToggle) {
            super(x, y, 13, 13, Component.literal(favorited ? "Unfavorite" : "Favorite"));
            this.favorited = favorited;
            this.onToggle = onToggle;
            setTooltip(Tooltip.create(Component.literal(
                    favorited ? "Favorited — Click to remove star" : "Click to pin friend to top")));
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            drawStar(g, getX() + 2, getY() + 2, this.favorited, this.isHoveredOrFocused());
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            playButtonClickSound(Minecraft.getInstance().getSoundManager());
            this.onToggle.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** Crisp vanilla tab widget using Minecraft's built-in widget/tab_* sprites. */
    public static final class TabButton extends AbstractWidget {
        private final boolean selected;
        private final Runnable onPress;

        public TabButton(int x, int y, int w, int h, Component label, boolean selected, Runnable onPress) {
            super(x, y, w, h, label);
            this.selected = selected;
            this.onPress = onPress;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            BurrowVanillaSprites.SpriteSpec sprite = this.selected
                    ? (this.isHoveredOrFocused() ? BurrowVanillaSprites.TAB_SELECTED_HOVER : BurrowVanillaSprites.TAB_SELECTED)
                    : (this.isHoveredOrFocused() ? BurrowVanillaSprites.TAB_HOVER : BurrowVanillaSprites.TAB_IDLE);
            BurrowVanillaSprites.draw(g, sprite, getX(), getY(), getWidth(), getHeight());
            Font font = font();
            int color = this.selected ? TEXT_PRIMARY : (this.isHoveredOrFocused() ? 0xFFE8E8EE : TEXT_SECONDARY);
            int textY = getY() + (getHeight() - 8) / 2 + (this.selected ? 0 : 1);
            g.centeredText(font, getMessage(), getX() + getWidth() / 2, textY, color);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (!this.selected) {
                playButtonClickSound(Minecraft.getInstance().getSoundManager());
                this.onPress.run();
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** Compact 20x20 vanilla icon button for Do Not Disturb (replaces Incognito). */
    public static final class DndButton extends AbstractWidget {
        private static final String[] BELL_ICON = {
                "......KK......",
                "....KKHHKK....",
                "...KHHHHHHK...",
                "..KHHBBBBHHK..",
                "..KHBBBBBBHK..",
                "..KBBBBBBBBK..",
                "..KBBBBBBBBK..",
                ".KBBBBBBBBBBK.",
                ".KDDDDDDDDDDK.",
                "KDDDDDDDDDDDDK",
                "KKKKKKKKKKKKKK",
                ".....KDDK.....",
                "......KK......"
        };

        private static final String[] DND_ON_ICON = {
                "...KKKKKK...",
                ".KKHHHHHHKK.",
                ".KHBBBBBBHK.",
                "KBBBBBBBBBBK",
                "KBBBBBBBBBBK",
                "KBBWWWWWWBBK",
                "KBBWWWWWWBBK",
                "KBBBBBBBBBBK",
                "KDDDDDDDDDDK",
                ".KDDDDDDDDK.",
                ".KKDDDDDDKK.",
                "...KKKKKK..."
        };

        private final boolean dnd;
        private final Runnable onToggle;

        public DndButton(int x, int y, boolean dnd, Runnable onToggle) {
            super(x, y, 20, 20, Component.literal(dnd ? "Do Not Disturb: On" : "Do Not Disturb: Off"));
            this.dnd = dnd;
            this.onToggle = onToggle;
            setTooltip(Tooltip.create(Component.literal(
                    dnd
                            ? "Do Not Disturb: On — Appearing as Do Not Disturb & muting popups"
                            : "Do Not Disturb: Off — Click to appear as Do Not Disturb & mute popups")));
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            BurrowVanillaSprites.SpriteSpec sprite = this.isHoveredOrFocused()
                    ? BurrowVanillaSprites.BUTTON_HOVER
                    : BurrowVanillaSprites.BUTTON_IDLE;
            BurrowVanillaSprites.draw(g, sprite, getX(), getY(), getWidth(), getHeight());

            if (this.dnd) {
                drawPixelArt(g, getX() + 4, getY() + 4, DND_ON_ICON,
                        0xFF1D1E26, 0xFFFF7A85, 0xFFE64855, 0xFFC02E3C, 0xFFFFFFFF, 0xFFFFFFFF);
            } else {
                drawPixelArt(g, getX() + 3, getY() + 3, BELL_ICON,
                        0xFF1D1E26, 0xFFFFEA8A, 0xFFEBC042, 0xFFC29024, 0xFFFFFFFF, 0xFFFFFFFF);
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            playButtonClickSound(Minecraft.getInstance().getSoundManager());
            this.onToggle.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** Compact 20x20 vanilla icon button for toggling Discord Rich Presence. */
    public static final class DiscordRpcButton extends AbstractWidget {
        private static final String[] CLYDE_ICON = {
                "..KKKK..KKKK..",
                ".KHHHHKKHHHHK.",
                ".KHHHHHHHHHHK.",
                "KBBBBBBBBBBBBK",
                "KBBWWBBBBWWBBK",
                "KBBWWBBBBWWBBK",
                "KBBBBBBBBBBBBK",
                "KDDDDDDDDDDDDK",
                ".KDDDKKKKDDDK.",
                ".KDDK....KDDK.",
                "..KK......KK.."
        };

        private final boolean enabled;
        private final boolean pausedByDnd;
        private final Runnable onToggle;

        public DiscordRpcButton(int x, int y, boolean enabled, boolean pausedByDnd, Runnable onToggle) {
            super(x, y, 20, 20, Component.literal(enabled ? "Discord RPC: On" : "Discord RPC: Off"));
            this.enabled = enabled;
            this.pausedByDnd = pausedByDnd;
            this.onToggle = onToggle;
            String tip;
            if (!enabled) {
                tip = "Discord Rich Presence: Off — Click to show your Minecraft status on Discord";
            } else if (pausedByDnd) {
                tip = "Discord Rich Presence: On (paused while Do Not Disturb is active)";
            } else {
                tip = "Discord Rich Presence: On — Click to hide your Minecraft status on Discord";
            }
            setTooltip(Tooltip.create(Component.literal(tip)));
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            BurrowVanillaSprites.SpriteSpec sprite = this.isHoveredOrFocused()
                    ? BurrowVanillaSprites.BUTTON_HOVER
                    : BurrowVanillaSprites.BUTTON_IDLE;
            BurrowVanillaSprites.draw(g, sprite, getX(), getY(), getWidth(), getHeight());

            int ox = getX() + 3;
            int oy = getY() + 4;
            if (!this.enabled) {
                drawPixelArt(g, ox, oy, CLYDE_ICON,
                        0xFF1D1E26, 0xFF9494A2, 0xFF747482, 0xFF585864, 0xFF282830, 0xFF282830);
                // Crisp dark-outlined red diagonal slash
                for (int i = 0; i < 12; i++) {
                    int sx = getX() + 4 + i;
                    int sy = getY() + 4 + i;
                    g.fill(sx, sy - 1, sx + 1, sy, 0xFF1D1E26);
                    g.fill(sx, sy + 1, sx + 1, sy + 2, 0xFF1D1E26);
                    g.fill(sx, sy, sx + 1, sy + 1, 0xFFE64855);
                }
            } else if (this.pausedByDnd) {
                drawPixelArt(g, ox, oy, CLYDE_ICON,
                        0xFF1D1E26, 0xFF98A0C4, 0xFF7A82A8, 0xFF60688C, 0xFFE6E8F2, 0xFFD0D4E4);
            } else {
                drawPixelArt(g, ox, oy, CLYDE_ICON,
                        0xFF1D1E26, 0xFF7B8BFF, 0xFF5865F2, 0xFF4752C4, 0xFFFFFFFF, 0xFFE6EAFF);
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            playButtonClickSound(Minecraft.getInstance().getSoundManager());
            this.onToggle.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private static void drawPixelArt(
            GuiGraphicsExtractor g,
            int ox,
            int oy,
            String[] grid,
            int colK,
            int colH,
            int colB,
            int colD,
            int colW,
            int colw
    ) {
        for (int row = 0; row < grid.length; row++) {
            String line = grid[row];
            for (int col = 0; col < line.length(); col++) {
                int argb = switch (line.charAt(col)) {
                    case 'K' -> colK;
                    case 'H' -> colH;
                    case 'B' -> colB;
                    case 'D' -> colD;
                    case 'W' -> colW;
                    case 'w' -> colw;
                    default -> 0;
                };
                if (argb != 0) {
                    g.fill(ox + col, oy + row, ox + col + 1, oy + row + 1, argb);
                }
            }
        }
    }

    /**
     * Reusable scissor-clipped, smooth-scrolling viewport widget with mouse-wheel
     * damping and a classic 6px vanilla Minecraft draggable scrollbar. Child widgets
     * are added at their un-scrolled screen coordinates (scroll = 0) and translated automatically.
     */
    public static final class ScrollContainer extends AbstractWidget {
        @FunctionalInterface
        public interface BackgroundRenderer {
            void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, int scrollPx);
        }

        private record ChildEntry(AbstractWidget widget, int baseY) {}

        private static final int SCROLLBAR_W = 6;

        private final List<ChildEntry> children = new ArrayList<>();
        private final DoubleConsumer onScrollChanged;
        private @Nullable BackgroundRenderer backgroundRenderer;
        private double targetScroll;
        private double visualScroll;
        private double maxScroll;
        private long lastFrameNanos = System.nanoTime();
        private boolean draggingScrollbar = false;
        private double dragStartY = 0.0;
        private double dragStartScroll = 0.0;
        private @Nullable AbstractWidget activeChild = null;

        public ScrollContainer(int x, int y, int w, int h, double initialScroll, DoubleConsumer onScrollChanged) {
            super(x, y, w, h, Component.empty());
            this.targetScroll = Math.max(0.0, initialScroll);
            this.visualScroll = this.targetScroll;
            this.onScrollChanged = onScrollChanged;
        }

        public void setBackgroundRenderer(@Nullable BackgroundRenderer renderer) {
            this.backgroundRenderer = renderer;
        }

        public void addChild(AbstractWidget widget) {
            this.children.add(new ChildEntry(widget, widget.getY()));
        }

        public void setContentHeight(int contentHeight) {
            this.maxScroll = Math.max(0.0, contentHeight - getHeight());
            this.targetScroll = Math.clamp(this.targetScroll, 0.0, this.maxScroll);
            this.visualScroll = Math.clamp(this.visualScroll, 0.0, this.maxScroll);
            this.onScrollChanged.accept(this.targetScroll);
            syncChildPositions((int) Math.round(this.visualScroll));
        }

        public boolean hasScrollbar() {
            return this.maxScroll > 0.5;
        }

        private void syncChildPositions(int scrollPx) {
            for (ChildEntry e : this.children) {
                e.widget.setY(e.baseY - scrollPx);
            }
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            long now = System.nanoTime();
            double dt = Math.min(0.1, Math.max(0.001, (now - this.lastFrameNanos) / 1_000_000_000.0));
            this.lastFrameNanos = now;

            if (this.draggingScrollbar) {
                this.visualScroll = this.targetScroll;
            } else {
                double diff = this.targetScroll - this.visualScroll;
                if (Math.abs(diff) < 0.25) {
                    this.visualScroll = this.targetScroll;
                } else {
                    double alpha = 1.0 - Math.exp(-20.0 * dt);
                    this.visualScroll += diff * alpha;
                }
            }

            int scrollPx = (int) Math.round(this.visualScroll);
            syncChildPositions(scrollPx);

            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();

            int trackX = x + w - SCROLLBAR_W - 1;
            boolean inViewport = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
            boolean onScrollbar = hasScrollbar() && inViewport && mouseX >= trackX - 1;
            int effMouseX = (inViewport && !onScrollbar && !this.draggingScrollbar) ? mouseX : -10000;
            int effMouseY = (inViewport && !onScrollbar && !this.draggingScrollbar) ? mouseY : -10000;

            g.enableScissor(x, y, x + w, y + h);
            if (this.backgroundRenderer != null) {
                this.backgroundRenderer.draw(g, effMouseX, effMouseY, scrollPx);
            }
            for (ChildEntry e : this.children) {
                int cy = e.widget.getY();
                int ch = e.widget.getHeight();
                // Allow a small margin below card bottom for expanded drawers drawn by card widgets
                if (cy + ch + 30 < y || cy > y + h + 4) {
                    continue;
                }
                e.widget.extractRenderState(g, effMouseX, effMouseY, partialTick);
            }
            g.disableScissor();

            // Authentic 6px vanilla Minecraft scrollbar (widget/scroller_background + widget/scroller)
            if (hasScrollbar()) {
                int trackY = y + 2;
                int trackH = Math.max(16, h - 4);
                BurrowVanillaSprites.draw(g, BurrowVanillaSprites.SCROLLER_BG, trackX, trackY, SCROLLBAR_W, trackH);

                int thumbH = Math.max(20, (int) Math.round((double) h * trackH / (h + this.maxScroll)));
                thumbH = Math.min(trackH, thumbH);
                int maxThumbTravel = Math.max(1, trackH - thumbH);
                int thumbY = trackY + (int) Math.round((this.visualScroll / this.maxScroll) * maxThumbTravel);

                BurrowVanillaSprites.draw(g, BurrowVanillaSprites.SCROLLER, trackX, thumbY, SCROLLBAR_W, thumbH);
                boolean hoverThumb = this.draggingScrollbar
                        || (mouseX >= trackX - 1 && mouseX <= trackX + SCROLLBAR_W + 1
                        && mouseY >= thumbY && mouseY <= thumbY + thumbH);
                if (hoverThumb) {
                    g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH,
                            this.draggingScrollbar ? 0x30FFFFFF : 0x1CFFFFFF);
                }
            }
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            if (!isMouseOver(mouseX, mouseY) || !hasScrollbar()) {
                return false;
            }
            this.targetScroll = Math.clamp(this.targetScroll - scrollY * 24.0, 0.0, this.maxScroll);
            this.onScrollChanged.accept(this.targetScroll);
            return true;
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (!isMouseOver(event.x(), event.y())) {
                return false;
            }
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            int trackX = x + w - SCROLLBAR_W - 1;

            // Scrollbar click/drag
            if (hasScrollbar() && event.button() == 0 && event.x() >= trackX - 1) {
                int trackY = y + 2;
                int trackH = Math.max(16, h - 4);
                int thumbH = Math.max(20, (int) Math.round((double) h * trackH / (h + this.maxScroll)));
                thumbH = Math.min(trackH, thumbH);
                int maxThumbTravel = Math.max(1, trackH - thumbH);
                int thumbY = trackY + (int) Math.round((this.visualScroll / this.maxScroll) * maxThumbTravel);

                if (event.y() < thumbY || event.y() > thumbY + thumbH) {
                    double ratio = Math.clamp((event.y() - trackY - thumbH / 2.0) / maxThumbTravel, 0.0, 1.0);
                    this.targetScroll = ratio * this.maxScroll;
                    this.visualScroll = this.targetScroll;
                    this.onScrollChanged.accept(this.targetScroll);
                }
                this.draggingScrollbar = true;
                this.dragStartY = event.y();
                this.dragStartScroll = this.targetScroll;
                return true;
            }

            syncChildPositions((int) Math.round(this.visualScroll));
            // Dispatch in reverse order so overlay icons (like StarButton) hit before cards underneath.
            for (int i = this.children.size() - 1; i >= 0; i--) {
                AbstractWidget child = this.children.get(i).widget;
                if (child.mouseClicked(event, doubleClick)) {
                    this.activeChild = child;
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
            if (this.draggingScrollbar && hasScrollbar()) {
                int h = getHeight();
                int trackH = Math.max(16, h - 4);
                int thumbH = Math.max(20, (int) Math.round((double) h * trackH / (h + this.maxScroll)));
                thumbH = Math.min(trackH, thumbH);
                int maxThumbTravel = Math.max(1, trackH - thumbH);
                double deltaScroll = ((event.y() - this.dragStartY) / maxThumbTravel) * this.maxScroll;
                this.targetScroll = Math.clamp(this.dragStartScroll + deltaScroll, 0.0, this.maxScroll);
                this.visualScroll = this.targetScroll;
                this.onScrollChanged.accept(this.targetScroll);
                return true;
            }
            if (this.activeChild != null) {
                return this.activeChild.mouseDragged(event, dx, dy);
            }
            return false;
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            boolean handled = false;
            if (this.draggingScrollbar) {
                this.draggingScrollbar = false;
                handled = true;
            }
            if (this.activeChild != null) {
                handled |= this.activeChild.mouseReleased(event);
                this.activeChild = null;
            }
            return handled;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }

    /** Crisp muted Burrow version label drawn in the bottom-right corner of Burrow screens. */
    public static void drawVersionFooter(GuiGraphicsExtractor g, int screenW, int screenH) {
        Font font = font();
        String label = "Burrow v" + BurrowClient.modVersion();
        int x = screenW - font.width(label) - 6;
        int y = screenH - 12;
        g.text(font, label, x, y, TEXT_MUTED, true);
    }

    /** Vertical pixels reserved inside the top of the Friends/Host inset box for an update notice banner (0 if none). */
    public static int updateBannerReservedHeight() {
        UpdateChecker.UpdateInfo info = UpdateChecker.info();
        if (!info.isAvailable()) {
            return 0;
        }
        if (info.isBreaking()) {
            return 38;
        }
        return UpdateChecker.isCompatibleBannerDismissed() ? 0 : 24;
    }

    /** Adds clickable Update / Dismiss buttons for the active update notice banner, if any. */
    public static void addUpdateBannerWidgets(
            int contentX,
            int contentY,
            int contentW,
            java.util.function.Consumer<AbstractWidget> addWidget
    ) {
        UpdateChecker.UpdateInfo info = UpdateChecker.info();
        if (!info.isAvailable()) {
            return;
        }
        int bx = contentX + 4;
        int by = contentY + 4;
        int bw = contentW - 8;
        if (info.isBreaking()) {
            int btnW = 58;
            VanillaButton updateBtn = button(
                    Component.literal("Update").withColor(0xFFFFD866),
                    bx + bw - btnW - 5, by + 8, btnW, 18,
                    UpdateChecker::openDownloadPage);
            updateBtn.setTooltip(Tooltip.create(Component.literal(
                    "Open Burrow v" + info.latestVersion() + " download page")));
            addWidget.accept(updateBtn);
        } else if (!UpdateChecker.isCompatibleBannerDismissed()) {
            int dismissW = 16;
            int dlW = 54;
            VanillaButton dlBtn = button(
                    Component.literal("Download").withColor(0xFF7CC8FF),
                    bx + bw - dismissW - dlW - 7, by + 2, dlW, 16,
                    UpdateChecker::openDownloadPage);
            dlBtn.setTooltip(Tooltip.create(Component.literal(
                    "Open Burrow v" + info.latestVersion() + " download page")));
            addWidget.accept(dlBtn);

            VanillaButton dismissBtn = button(
                    Component.literal("x").withColor(TEXT_SECONDARY),
                    bx + bw - dismissW - 3, by + 2, dismissW, 16,
                    UpdateChecker::dismissCompatibleBanner);
            dismissBtn.setTooltip(Tooltip.create(Component.literal("Dismiss for this session")));
            addWidget.accept(dismissBtn);
        }
    }

    /** Draws either the slim compatible-update bar (0.5.X) or the prominent breaking-update banner (0.X.0 / X.0.0). */
    public static void drawUpdateBanner(GuiGraphicsExtractor g, int contentX, int contentY, int contentW) {
        UpdateChecker.UpdateInfo info = UpdateChecker.info();
        if (!info.isAvailable()) {
            return;
        }
        Font font = font();
        int bx = contentX + 4;
        int by = contentY + 4;
        int bw = contentW - 8;

        if (info.isBreaking()) {
            int bh = 34;
            g.fill(bx, by, bx + bw, by + bh, 0xFF34181E);
            g.outline(bx, by, bw, bh, 0xFFC84B5B);
            g.fill(bx + 1, by + 1, bx + 4, by + bh - 1, DOT_DND);

            String title = "! Major Update Available: v" + info.latestVersion();
            String sub = "Installed v" + info.currentVersion() + " may not work with the server";
            int maxW = bw - 74;
            g.text(font, BurrowFriendsScreen.ellipsize(font, title, maxW), bx + 9, by + 6, 0xFFFF98A2, true);
            g.text(font, BurrowFriendsScreen.ellipsize(font, sub, maxW), bx + 9, by + 19, 0xFFE6CFD3, false);
        } else if (!UpdateChecker.isCompatibleBannerDismissed()) {
            int bh = 20;
            g.fill(bx, by, bx + bw, by + bh, 0xFF1A2633);
            g.outline(bx, by, bw, bh, 0xFF3B6691);
            g.fill(bx + 1, by + 1, bx + 3, by + bh - 1, DOT_NOTIFY);

            String msg = "Update available: v" + info.latestVersion() + " (compatible)";
            int maxW = bw - 86;
            g.text(font, BurrowFriendsScreen.ellipsize(font, msg, maxW), bx + 8, by + 6, 0xFFD4ECFF, false);
        }
    }

    /**
     * Prominent once-per-session popup modal shown when a breaking Burrow update
     * ({@code 0.X.0} or {@code X.0.0}) is available.
     */
    public static final class BreakingUpdateScreen extends Screen {
        private static final int MODAL_W = 304;
        private static final int MODAL_H = 128;

        private final Screen parent;
        private final UpdateChecker.UpdateInfo info;
        private int modalX;
        private int modalY;

        public BreakingUpdateScreen(Screen parent, UpdateChecker.UpdateInfo info) {
            super(Component.literal("Major Burrow Update Available"));
            this.parent = parent;
            this.info = info;
        }

        @Override
        protected void init() {
            this.modalX = (this.width - MODAL_W) / 2;
            this.modalY = Math.max(4, (this.height - MODAL_H) / 2);

            int btnY = this.modalY + MODAL_H - 30;
            int innerX = this.modalX + PAD;
            int innerW = MODAL_W - PAD * 2;
            int dlW = 148;
            int contW = innerW - dlW - 6;

            addRenderableWidget(button(
                    Component.literal("Download v" + this.info.latestVersion()).withColor(0xFF7AE89B),
                    innerX, btnY, dlW, 20,
                    UpdateChecker::openDownloadPage));

            addRenderableWidget(button("Continue Anyway", innerX + dlW + 6, btnY, contW, 20, this::onClose));
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            super.extractBackground(g, mouseX, mouseY, partialTick);
            drawModalPanel(g, this.modalX, this.modalY, MODAL_W, MODAL_H);

            Font font = font();
            int innerX = this.modalX + PAD;
            int innerW = MODAL_W - PAD * 2;

            g.text(font, "! Major Burrow Update Available", innerX, this.modalY + 13, 0xFFFF909C, true);

            int boxY = this.modalY + 28;
            int boxH = 62;
            g.fill(innerX, boxY, innerX + innerW, boxY + boxH, 0xFF28161B);
            g.outline(innerX, boxY, innerW, boxH, 0xFFA6404E);
            g.fill(innerX + 1, boxY + 1, innerX + 4, boxY + boxH - 1, DOT_DND);

            g.text(font,
                    "Installed: v" + this.info.currentVersion() + "   ->   Latest: v" + this.info.latestVersion(),
                    innerX + 10, boxY + 9, 0xFFFFD866, true);
            g.text(font,
                    "This is a breaking update with protocol or feature",
                    innerX + 10, boxY + 25, TEXT_PRIMARY, false);
            g.text(font,
                    "changes. Older versions may fail to connect or join.",
                    innerX + 10, boxY + 37, TEXT_PRIMARY, false);
            g.text(font,
                    "Download the latest jar and restart Minecraft.",
                    innerX + 10, boxY + 49, TEXT_SECONDARY, false);

            drawVersionFooter(g, this.width, this.height);
        }

        @Override
        public void onClose() {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(this.parent);
            }
        }
    }
}
