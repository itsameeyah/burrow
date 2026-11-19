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

/**
 * Shared vanilla-styled visual primitives for Burrow screens (Host World and
 * Friends) using Minecraft 1.21.11's built-in 9-slice panel, tab & button sprites.
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
    public static final int DOT_NOTIFY = 0xFF55BBFF;
    public static final int DOT_OFFLINE = 0xFF70707A;

    public static final int ROLE_HOST = 0xFFFFD866;
    public static final int ROLE_OP = 0xFFFF88B8;
    public static final int ROLE_MEMBER = 0xFF7AE89B;
    public static final int ROLE_VISITOR = 0xFF7CC8FF;

    private BurrowUi() {}

    /** Outer vanilla 9-slice modal panel (uses the sprite's own clean border, no extra halo). */
    public static void drawModalPanel(GuiGraphics g, int x, int y, int w, int h) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL_BG, x, y, w, h);
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
            Identifier sprite = this.selected
                    ? (this.isHoveredOrFocused() ? TAB_SELECTED_HOVER : TAB_SELECTED)
                    : (this.isHoveredOrFocused() ? TAB_HOVER : TAB_IDLE);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, getX(), getY(), getWidth(), getHeight());
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

    /** Compact 20x20 vanilla icon button with a crisp pixel eye icon for Incognito mode. */
    public static final class IncognitoButton extends AbstractWidget {
        private final boolean incognito;
        private final Runnable onToggle;

        public IncognitoButton(int x, int y, boolean incognito, Runnable onToggle) {
            super(x, y, 20, 20, Component.literal(incognito ? "Incognito: On" : "Incognito: Off"));
            this.incognito = incognito;
            this.onToggle = onToggle;
            setTooltip(Tooltip.create(Component.literal(
                    incognito
                            ? "Incognito: On — Friends only see \"Playing Multiplayer\""
                            : "Incognito: Off — Click to hide your server & world details")));
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            Identifier sprite = this.isHoveredOrFocused() ? BUTTON_HOVER : BUTTON_IDLE;
            g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, getX(), getY(), getWidth(), getHeight());

            int left = getX();
            int top = getY();
            int fg = this.incognito ? 0xFFD8A8FF : 0xFFEAEAF0;

            // Symmetrical 12x7 pixel-art almond eye centered inside the 20x20
            // vanilla button interior (x in [4..16), y in [6..13), center row y=9).
            g.fill(left + 8, top + 6, left + 12, top + 7, fg);
            g.fill(left + 6, top + 7, left + 8, top + 8, fg);
            g.fill(left + 12, top + 7, left + 14, top + 8, fg);
            g.fill(left + 5, top + 8, left + 6, top + 9, fg);
            g.fill(left + 14, top + 8, left + 15, top + 9, fg);
            g.fill(left + 4, top + 9, left + 5, top + 10, fg);
            g.fill(left + 15, top + 9, left + 16, top + 10, fg);
            g.fill(left + 5, top + 10, left + 6, top + 11, fg);
            g.fill(left + 14, top + 10, left + 15, top + 11, fg);
            g.fill(left + 6, top + 11, left + 8, top + 12, fg);
            g.fill(left + 12, top + 11, left + 14, top + 12, fg);
            g.fill(left + 8, top + 12, left + 12, top + 13, fg);

            if (!this.incognito) {
                // Centered rounded 4x3 pupil (symmetric around x=10, y=9)
                g.fill(left + 9, top + 8, left + 11, top + 11, fg);
                g.fill(left + 8, top + 9, left + 12, top + 10, fg);
            } else {
                // Centered diagonal slash across the eye when Incognito is ON
                for (int i = -4; i <= 4; i++) {
                    g.fill(left + 9 + i, top + 9 + i, left + 11 + i, top + 10 + i, 0xFFFF88B8);
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
}
