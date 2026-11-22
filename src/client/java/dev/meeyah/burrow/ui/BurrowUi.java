package dev.meeyah.burrow.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
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

    public static final Identifier PANEL_BG = Identifier.withDefaultNamespace("popup/background");
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

    /**
     * Classic Minecraft bevelled silver-frame modal panel with a recessed dark
     * slate interior, matching Minecraft 26.2+'s friends/background style
     * (uses an explicit burrow:panel_bg resource-pack override when provided).
     */
    public static void drawModalPanel(GuiGraphics g, int x, int y, int w, int h) {
        if (BurrowVanillaSprites.drawCustomOverrideIfPresent(g, BurrowVanillaSprites.PANEL_BG, x, y, w, h)) {
            return;
        }
        int r = x + w;
        int b = y + h;
        final int BLACK = 0xFF000000;
        final int WHITE = 0xFFFFFFFF;
        final int LIGHT = 0xFFC6C6C6;
        final int DARK  = 0xFF555555;
        final int INNER = 0xFF373737;

        g.fill(x + 2, y, r - 3, b - 1, BLACK);
        g.fill(x + 1, y + 1, r - 2, b - 2, BLACK);
        g.fill(x, y + 2, r - 1, b - 3, BLACK);
        g.fill(x + 3, y + 3, r, b - 2, BLACK);
        g.fill(x + 2, y + 2, r - 1, b - 1, BLACK);
        g.fill(x + 3, y + 2, r - 2, b, BLACK);

        g.fill(x + 2, y + 1, r - 3, y + 3, WHITE);
        g.fill(x + 1, y + 2, x + 3, b - 3, WHITE);
        g.fill(x + 3, y + 3, x + 4, y + 4, WHITE);

        g.fill(x + 3, b - 3, r - 2, b - 1, DARK);
        g.fill(r - 3, y + 3, r - 1, b - 2, DARK);
        g.fill(r - 4, b - 4, r - 3, b - 3, DARK);

        g.fill(x + 3, y + 3, r - 3, b - 3, LIGHT);
        g.fill(x + 3, y + 3, x + 4, y + 4, WHITE);
        g.fill(r - 4, b - 4, r - 3, b - 3, DARK);
        g.fill(r - 3, y + 2, r - 2, y + 3, LIGHT);
        g.fill(x + 2, b - 3, x + 3, b - 2, LIGHT);

        g.fill(x + 7, y + 7, r - 7, b - 7, WHITE);
        g.fill(x + 7, y + 7, r - 8, y + 8, BLACK);
        g.fill(x + 7, y + 7, x + 8, b - 8, BLACK);
        g.fill(r - 8, y + 7, r - 7, y + 8, DARK);
        g.fill(x + 7, b - 8, x + 8, b - 7, DARK);

        g.fill(x + 8, y + 8, r - 8, b - 8, INNER);
    }

    /** Crisp flat dark recessed container inside a modal panel. */
    public static void drawInsetBox(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, INSET_BG);
        g.renderOutline(x, y, w, h, INSET_BORDER);
    }

    /** Small pill badge with leading status dot (aligned to rightX). */
    public static void drawStatusPill(
            GuiGraphics g,
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
        g.renderOutline(x, y, pillW, pillH, 0xFF0B0B0E);
        g.fill(x + 6, y + 6, x + 10, y + 10, dotColor);
        g.drawString(font, text, x + 14, y + 4, TEXT_PRIMARY, false);
    }

    /** Compact colored tag pill at crisp native 1.0x pixel font scale with balanced padding. */
    public static int drawTagPill(GuiGraphics g, Font font, int x, int y, String label, int color) {
        int textW = font.width(label);
        int w = textW + 6;
        int h = 11;
        int bg = (color & 0x00FFFFFF) | 0x28000000;
        int border = (color & 0x00FFFFFF) | 0x55000000;
        g.fill(x, y, x + w, y + h, bg);
        g.renderOutline(x, y, w, h, border);
        // Native 1.0x glyph (7px tall) centered cleanly inside 11px height: 2px top, 2px bottom, 3px left/right
        g.drawString(font, label, x + 3, y + 2, color, false);
        return w;
    }

    /** Crisp 9x9 pixel-art 5-point star icon (filled gold when favorited, clean outline otherwise). */
    public static void drawStar(GuiGraphics g, int x, int y, boolean favorited, boolean hovered) {
        int c = favorited ? STAR_FAVORITE : (hovered ? STAR_HOVER : STAR_IDLE);
        if (favorited) {
            g.fill(x + 4, y, x + 5, y + 2, c);
            g.fill(x + 3, y + 2, x + 6, y + 3, c);
            g.fill(x, y + 3, x + 9, y + 4, c);
            g.fill(x + 1, y + 4, x + 8, y + 5, c);
            g.fill(x + 2, y + 5, x + 7, y + 6, c);
            g.fill(x + 2, y + 6, x + 4, y + 8, c);
            g.fill(x + 5, y + 6, x + 7, y + 8, c);
            g.fill(x + 1, y + 7, x + 3, y + 9, c);
            g.fill(x + 6, y + 7, x + 8, y + 9, c);
        } else {
            // Crisp hollow 9x9 star outline
            g.fill(x + 4, y, x + 5, y + 2, c);
            g.fill(x + 3, y + 2, x + 4, y + 3, c);
            g.fill(x + 5, y + 2, x + 6, y + 3, c);
            g.fill(x, y + 3, x + 3, y + 4, c);
            g.fill(x + 6, y + 3, x + 9, y + 4, c);
            g.fill(x + 1, y + 4, x + 2, y + 5, c);
            g.fill(x + 7, y + 4, x + 8, y + 5, c);
            g.fill(x + 2, y + 5, x + 3, y + 6, c);
            g.fill(x + 6, y + 5, x + 7, y + 6, c);
            g.fill(x + 2, y + 6, x + 3, y + 7, c);
            g.fill(x + 4, y + 6, x + 5, y + 7, c);
            g.fill(x + 6, y + 6, x + 7, y + 7, c);
            g.fill(x + 1, y + 7, x + 3, y + 9, c);
            g.fill(x + 6, y + 7, x + 8, y + 9, c);
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
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
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

    /** Crisp vanilla tab widget using Minecraft's widget/tab_* sprites. */
    public static final class TabButton extends AbstractWidget {
        private final boolean selected;
        private final Runnable onPress;

        public TabButton(int x, int y, int w, int h, Component label, boolean selected, Runnable onPress) {
            super(x, y, w, h, label);
            this.selected = selected;
            this.onPress = onPress;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            BurrowVanillaSprites.SpriteSpec sprite = this.selected
                    ? (this.isHoveredOrFocused() ? BurrowVanillaSprites.TAB_SELECTED_HOVER : BurrowVanillaSprites.TAB_SELECTED)
                    : (this.isHoveredOrFocused() ? BurrowVanillaSprites.TAB_HOVER : BurrowVanillaSprites.TAB_IDLE);
            BurrowVanillaSprites.draw(g, sprite, getX(), getY(), getWidth(), getHeight());
            Font font = Minecraft.getInstance().font;
            int color = this.selected ? TEXT_PRIMARY : (this.isHoveredOrFocused() ? 0xFFE8E8EE : TEXT_SECONDARY);
            int textY = getY() + (getHeight() - 8) / 2 + (this.selected ? 0 : 1);
            g.drawCenteredString(font, getMessage(), getX() + getWidth() / 2, textY, color);
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
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            BurrowVanillaSprites.SpriteSpec sprite = this.isHoveredOrFocused()
                    ? BurrowVanillaSprites.BUTTON_HOVER
                    : BurrowVanillaSprites.BUTTON_IDLE;
            BurrowVanillaSprites.draw(g, sprite, getX(), getY(), getWidth(), getHeight());

            int left = getX();
            int top = getY();
            if (this.dnd) {
                // Filled coral-red DND circle (10x10) with horizontal white minus bar
                int red = DOT_DND;
                g.fill(left + 7, top + 5, left + 13, top + 6, red);
                g.fill(left + 6, top + 6, left + 14, top + 7, red);
                g.fill(left + 5, top + 7, left + 15, top + 13, red);
                g.fill(left + 6, top + 13, left + 14, top + 14, red);
                g.fill(left + 7, top + 14, left + 13, top + 15, red);
                // Crisp horizontal bar across the middle
                g.fill(left + 7, top + 9, left + 13, top + 11, 0xFF1A1A1E);
            } else {
                // Crisp silver outline circle with subtle minus bar (inactive DND icon)
                int fg = 0xFFD8D8E4;
                g.fill(left + 7, top + 5, left + 13, top + 6, fg);
                g.fill(left + 6, top + 6, left + 7, top + 7, fg);
                g.fill(left + 13, top + 6, left + 14, top + 7, fg);
                g.fill(left + 5, top + 7, left + 6, top + 13, fg);
                g.fill(left + 14, top + 7, left + 15, top + 13, fg);
                g.fill(left + 6, top + 13, left + 7, top + 14, fg);
                g.fill(left + 13, top + 13, left + 14, top + 14, fg);
                g.fill(left + 7, top + 14, left + 13, top + 15, fg);
                // Center dash
                g.fill(left + 7, top + 9, left + 13, top + 11, fg);
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
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            BurrowVanillaSprites.SpriteSpec sprite = this.isHoveredOrFocused()
                    ? BurrowVanillaSprites.BUTTON_HOVER
                    : BurrowVanillaSprites.BUTTON_IDLE;
            BurrowVanillaSprites.draw(g, sprite, getX(), getY(), getWidth(), getHeight());

            int left = getX();
            int top = getY();
            int fg = !this.enabled
                    ? 0xFF7C7C8A
                    : (this.pausedByDnd ? 0xFFA5A8C8 : 0xFF8A9AFF);
            int eye = 0xFF1A1A22;

            // Crisp 12x9 stylized Discord-gamepad silhouette centered in 20x20
            g.fill(left + 6, top + 6, left + 8, top + 7, fg);
            g.fill(left + 12, top + 6, left + 14, top + 7, fg);
            g.fill(left + 5, top + 7, left + 15, top + 8, fg);
            g.fill(left + 4, top + 8, left + 16, top + 12, fg);
            g.fill(left + 5, top + 12, left + 15, top + 13, fg);
            g.fill(left + 5, top + 13, left + 8, top + 14, fg);
            g.fill(left + 12, top + 13, left + 15, top + 14, fg);
            // Two symmetric eyes inside silhouette
            g.fill(left + 7, top + 9, left + 9, top + 11, eye);
            g.fill(left + 11, top + 9, left + 13, top + 11, eye);

            if (!this.enabled) {
                for (int i = -4; i <= 4; i++) {
                    g.fill(left + 9 + i, top + 9 + i, left + 11 + i, top + 10 + i, 0xFFFF8A8A);
                }
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

    /**
     * Reusable scissor-clipped, smooth-scrolling viewport widget with mouse-wheel
     * damping and a draggable right-edge scrollbar. Child widgets are added at
     * their un-scrolled screen coordinates (scroll = 0) and translated automatically.
     */
    public static final class ScrollContainer extends AbstractWidget {
        @FunctionalInterface
        public interface BackgroundRenderer {
            void draw(GuiGraphics g, int mouseX, int mouseY, int scrollPx);
        }

        private record ChildEntry(AbstractWidget widget, int baseY) {}

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
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
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

            boolean inViewport = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
            boolean onScrollbar = hasScrollbar() && inViewport && mouseX >= x + w - 7;
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
                e.widget.render(g, effMouseX, effMouseY, partialTick);
            }
            g.disableScissor();

            // Crisp right-edge scrollbar track & thumb
            if (hasScrollbar()) {
                int trackX = x + w - 5;
                int trackY = y + 3;
                int trackH = Math.max(12, h - 6);
                g.fill(trackX, trackY, trackX + 3, trackY + trackH, 0x550A0A0E);

                int thumbH = Math.max(16, (int) Math.round((double) h * trackH / (h + this.maxScroll)));
                thumbH = Math.min(trackH, thumbH);
                int maxThumbTravel = Math.max(1, trackH - thumbH);
                int thumbY = trackY + (int) Math.round((this.visualScroll / this.maxScroll) * maxThumbTravel);

                boolean hoverThumb = this.draggingScrollbar
                        || (mouseX >= trackX - 2 && mouseX <= trackX + 5 && mouseY >= thumbY && mouseY <= thumbY + thumbH);
                int thumbColor = this.draggingScrollbar
                        ? 0xFF9898B0
                        : (hoverThumb ? 0xFF7A7A90 : 0xFF565668);
                g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, thumbColor);
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

            // Scrollbar click/drag
            if (hasScrollbar() && event.button() == 0 && event.x() >= x + w - 8) {
                int trackY = y + 3;
                int trackH = Math.max(12, h - 6);
                int thumbH = Math.max(16, (int) Math.round((double) h * trackH / (h + this.maxScroll)));
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
                int trackH = Math.max(12, h - 6);
                int thumbH = Math.max(16, (int) Math.round((double) h * trackH / (h + this.maxScroll)));
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

    /** Compact 20x20 vanilla icon button with a crisp pixel-art two-heads Friends icon. */
    public static final class FriendsButton extends AbstractWidget {
        private static final String[] ICON = {
                ".....KKKKKKKKKK.",
                ".....KOOooOOOOOK",
                ".....KOOOOOOOOOK",
                ".....KOAAAaAAOOK",
                ".....KAAAAAAAAOK",
                ".....KAWGAaAGWAK",
                ".KKKKKKKKKKAAaAK",
                ".KHHhHHHHHKAAMAK",
                ".KHHHHHHHHKaaaAK",
                ".KHSSSSSSHKKKKK.",
                ".KSSSSSSSSK.....",
                ".KSWBSsSBWK.....",
                ".KSSSNNSssK.....",
                ".KSSDDDDsSK.....",
                ".KSSsSSsSSK.....",
                ".KKKKKKKKKK....."
        };

        private final Runnable onPress;

        public FriendsButton(int x, int y, Runnable onPress) {
            super(x, y, 20, 20, Component.literal("Friends"));
            this.onPress = onPress;
            setTooltip(Tooltip.create(Component.literal("Friends")));
        }

        private static int iconColor(char c) {
            return switch (c) {
                case 'K' -> 0xFF1E1E1F;
                case 'O' -> 0xFFD97D2B;
                case 'o' -> 0xFFB85E1E;
                case 'A' -> 0xFFF5D8BA;
                case 'a' -> 0xFFE0BA96;
                case 'G' -> 0xFF2D7A32;
                case 'W' -> 0xFFFFFFFF;
                case 'M' -> 0xFFD4927B;
                case 'H' -> 0xFF4A2B17;
                case 'h' -> 0xFF382010;
                case 'S' -> 0xFFD49E72;
                case 's' -> 0xFFBD845A;
                case 'B' -> 0xFF523CA6;
                case 'N' -> 0xFFA66B46;
                case 'D' -> 0xFF6B3E26;
                default -> 0;
            };
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            Identifier sprite = this.isHoveredOrFocused() ? BUTTON_HOVER : BUTTON_IDLE;
            g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, getX(), getY(), getWidth(), getHeight());
            int ox = getX() + 2;
            int oy = getY() + 2;
            for (int row = 0; row < ICON.length; row++) {
                String line = ICON[row];
                for (int col = 0; col < line.length(); col++) {
                    int argb = iconColor(line.charAt(col));
                    if (argb != 0) {
                        g.fill(ox + col, oy + row, ox + col + 1, oy + row + 1, argb);
                    }
                }
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            playButtonClickSound(Minecraft.getInstance().getSoundManager());
            this.onPress.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
