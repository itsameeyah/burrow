package dev.meeyah.burrow.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Shared vanilla-styled visual primitives for Burrow screens (Host World and
 * Friends) using Minecraft 26.2's built-in 9-slice panel & tab sprites.
 */
public final class BurrowUi {
    public static final Identifier PANEL_BG = Identifier.withDefaultNamespace("friends/background");
    public static final Identifier PANEL_DARK = Identifier.withDefaultNamespace("friends/background_dark");
    public static final Identifier TAB_IDLE = Identifier.withDefaultNamespace("widget/tab");
    public static final Identifier TAB_SELECTED = Identifier.withDefaultNamespace("widget/tab_selected");
    public static final Identifier TAB_HOVER = Identifier.withDefaultNamespace("widget/tab_highlighted");
    public static final Identifier TAB_SELECTED_HOVER = Identifier.withDefaultNamespace("widget/tab_selected_highlighted");

    // Full-alpha ARGB palette tuned for vanilla dark panels.
    public static final int TEXT_PRIMARY = 0xFFFFFFFF;
    public static final int TEXT_SECONDARY = 0xFFB8B8C0;
    public static final int TEXT_MUTED = 0xFF8C8C96;
    public static final int BORDER_SUBTLE = 0x44FFFFFF;
    public static final int CARD_BG = 0x66121216;
    public static final int CARD_HOVER = 0x8820202A;
    public static final int CARD_SELECTED = 0x99282836;
    public static final int DRAWER_BG = 0x880B0B10;

    public static final int DOT_LIVE = 0xFF55EE77;
    public static final int DOT_LOCKED = 0xFFFFBB44;
    public static final int DOT_OFFLINE = 0xFF7A7A85;

    public static final int ROLE_HOST = 0xFFFFD866;
    public static final int ROLE_OP = 0xFFFF88B8;
    public static final int ROLE_MEMBER = 0xFF7AE89B;
    public static final int ROLE_VISITOR = 0xFF7CC8FF;

    private BurrowUi() {}

    /** Outer vanilla 9-slice modal panel with subtle outer drop border. */
    public static void drawModalPanel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x88000000);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL_BG, x, y, w, h);
    }

    /** Clean, flat recessed dark container inside a modal panel (no double grey frame). */
    public static void drawInsetBox(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0x88141418);
        g.outline(x, y, w, h, 0xCC0A0A0D);
        // Subtle 1px inner highlight along bottom/right for a crisp recessed feel
        g.horizontalLine(x + 1, x + w - 2, y + h - 1, 0x22FFFFFF);
        g.verticalLine(x + w - 1, y + 1, y + h - 2, 0x18FFFFFF);
    }

    /** Small pill badge with optional leading status dot (aligned to rightX). */
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
        g.fill(x, y, x + pillW, y + pillH, 0x99101015);
        g.outline(x, y, pillW, pillH, 0x33FFFFFF);
        // 4x4 status indicator dot
        g.fill(x + 6, y + 6, x + 10, y + 10, dotColor);
        g.text(font, text, x + 14, y + 4, TEXT_PRIMARY, true);
    }

    /** Compact colored role pill (returns total pixel width drawn). */
    public static int drawTagPill(GuiGraphicsExtractor g, Font font, int x, int y, String label, int color) {
        int w = font.width(label) + 8;
        int h = 11;
        int bg = (color & 0x00FFFFFF) | 0x2A000000;
        int border = (color & 0x00FFFFFF) | 0x66000000;
        g.fill(x, y, x + w, y + h, bg);
        g.outline(x, y, w, h, border);
        g.text(font, label, x + 4, y + 2, color, false);
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
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Identifier sprite = this.selected
                    ? (this.isHoveredOrFocused() ? TAB_SELECTED_HOVER : TAB_SELECTED)
                    : (this.isHoveredOrFocused() ? TAB_HOVER : TAB_IDLE);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, getX(), getY(), getWidth(), getHeight());
            Font font = Minecraft.getInstance().font;
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
}
