package dev.meeyah.burrow.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Shared vanilla-styled visual primitives for Burrow screens (Host World and
 * Friends) using Minecraft 1.21.1's built-in 9-slice panel, tab & button sprites.
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

    public static final ResourceLocation PANEL_BG = ResourceLocation.withDefaultNamespace("popup/background");
    public static final ResourceLocation TAB_IDLE = ResourceLocation.withDefaultNamespace("widget/tab");
    public static final ResourceLocation TAB_SELECTED = ResourceLocation.withDefaultNamespace("widget/tab_selected");
    public static final ResourceLocation TAB_HOVER = ResourceLocation.withDefaultNamespace("widget/tab_highlighted");
    public static final ResourceLocation TAB_SELECTED_HOVER = ResourceLocation.withDefaultNamespace("widget/tab_selected_highlighted");
    public static final ResourceLocation BUTTON_IDLE = ResourceLocation.withDefaultNamespace("widget/button");
    public static final ResourceLocation BUTTON_HOVER = ResourceLocation.withDefaultNamespace("widget/button_highlighted");

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

    /**
     * Classic Minecraft bevelled silver-frame modal panel with a recessed dark
     * slate interior, matching Minecraft 26.2+'s friends/background style.
     */
    public static void drawModalPanel(GuiGraphics g, int x, int y, int w, int h) {
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
            ResourceLocation sprite = this.selected
                    ? (this.isHoveredOrFocused() ? TAB_SELECTED_HOVER : TAB_SELECTED)
                    : (this.isHoveredOrFocused() ? TAB_HOVER : TAB_IDLE);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            g.blitSprite(sprite, getX(), getY(), getWidth(), getHeight());
            RenderSystem.disableBlend();
            Font font = Minecraft.getInstance().font;
            int color = this.selected ? TEXT_PRIMARY : (this.isHoveredOrFocused() ? 0xFFE8E8EE : TEXT_SECONDARY);
            int textY = getY() + (getHeight() - 8) / 2 + (this.selected ? 0 : 1);
            g.drawCenteredString(font, getMessage(), getX() + getWidth() / 2, textY, color);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            if (!this.selected) {
                playDownSound(Minecraft.getInstance().getSoundManager());
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
            ResourceLocation sprite = this.isHoveredOrFocused() ? BUTTON_HOVER : BUTTON_IDLE;
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            g.blitSprite(sprite, getX(), getY(), getWidth(), getHeight());
            RenderSystem.disableBlend();

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
        public void onClick(double mouseX, double mouseY) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            this.onToggle.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
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
            ResourceLocation sprite = this.isHoveredOrFocused() ? BUTTON_HOVER : BUTTON_IDLE;
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            g.blitSprite(sprite, getX(), getY(), getWidth(), getHeight());
            RenderSystem.disableBlend();
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
        public void onClick(double mouseX, double mouseY) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            this.onPress.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
