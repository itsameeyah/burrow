package dev.meeyah.burrow.ui;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.compat.ViaFabricPlusCompat;
import dev.meeyah.burrow.config.BurrowConfig;
import dev.meeyah.burrow.net.BurrowConnection;
import dev.meeyah.burrow.session.JoinFlow;
import dev.meeyah.burrow.state.FriendsState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Renders joinable Burrow worlds (Invited, Friends, Friends of Friends) and the
 * "Join by Code" input + live preview card at the very bottom of Minecraft's
 * vanilla Multiplayer server list.
 */
public final class BurrowMultiplayerWorlds {
    private static final Identifier JOIN_SPRITE = Identifier.withDefaultNamespace("server_list/join");
    private static final Identifier JOIN_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("server_list/join_highlighted");
    private static final Identifier INCOMPATIBLE_SPRITE = Identifier.withDefaultNamespace("server_list/incompatible");

    private BurrowMultiplayerWorlds() {}

    /** Duck interface implemented by {@code ServerSelectionListMixin}. */
    public interface BurrowServerListAccess {
        void burrow$refreshBurrowWorlds();
        boolean burrow$handleCodeKeyPressed(KeyEvent event);
    }

    public record JoinableWorld(
            UUID host,
            String hostName,
            String worldName,
            String badge,
            int badgeColor,
            String sourceDesc,
            int playerCount,
            int maxPlayers,
            boolean versionMismatch,
            boolean vfpCompatible,
            String gameVersion,
            boolean favorite
    ) {
        public boolean canJoin() {
            return !versionMismatch || vfpCompatible;
        }
    }

    /** Builds fresh ServerSelectionList entries for all currently joinable Burrow worlds. */
    public static List<ServerSelectionList.Entry> buildEntries() {
        List<JoinableWorld> worlds = collectJoinableWorlds();
        List<ServerSelectionList.Entry> out = new ArrayList<>(worlds.size());
        for (JoinableWorld w : worlds) {
            out.add(new WorldEntry(w));
        }
        return out;
    }

    /** Builds a preview entry for the current CodePreview if one is active. */
    public static @Nullable ServerSelectionList.Entry buildCodePreviewEntry() {
        FriendsState.CodePreview preview = BurrowClient.friends().codePreview();
        return preview == null ? null : new CodePreviewEntry(preview);
    }

    /**
     * Collects all currently joinable Burrow worlds deduplicated by host UUID,
     * with favorited friends pinned to the top within Invited -> Friends -> FoF.
     */
    public static List<JoinableWorld> collectJoinableWorlds() {
        FriendsState state = BurrowClient.friends();
        BurrowConfig cfg = BurrowClient.config();
        state.pruneExpired();

        Map<UUID, FriendsState.Friend> friendByUuid = new HashMap<>();
        Set<String> favNamesLower = new HashSet<>();
        for (FriendsState.Friend f : state.friends()) {
            if (f.uuid() != null) {
                friendByUuid.put(f.uuid(), f);
                if (cfg != null && cfg.isFavorite(f.uuid()) && f.name() != null) {
                    favNamesLower.add(f.name().toLowerCase(Locale.ROOT));
                }
            }
        }
        Map<UUID, FriendsState.DiscoverableWorld> discByUuid = new HashMap<>();
        for (FriendsState.DiscoverableWorld d : state.discoverableWorlds()) {
            if (d.host() != null) {
                discByUuid.put(d.host(), d);
            }
        }

        List<JoinableWorld> invitedList = new ArrayList<>();
        List<JoinableWorld> friendsList = new ArrayList<>();
        List<JoinableWorld> discoverableList = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();

        // 1. Active incoming world invites
        for (FriendsState.WorldInvite inv : state.worldInvites()) {
            if (inv.host() == null || !seen.add(inv.host())) continue;
            FriendsState.Friend f = friendByUuid.get(inv.host());
            FriendsState.DiscoverableWorld d = discByUuid.get(inv.host());

            boolean locked = (f != null && f.locked()) || (d != null && d.locked());
            if (locked) continue;

            int pCount = f != null ? f.playerCount() : (d != null ? d.playerCount() : 0);
            int pMax = f != null ? f.maxPlayers() : (d != null ? d.maxPlayers() : 0);
            boolean mismatch = (f != null && f.isVersionMismatch()) || (d != null && d.isVersionMismatch());
            String gameVer = f != null && !f.gameVersion().isEmpty()
                    ? f.gameVersion()
                    : (d != null ? d.gameVersion() : "");
            boolean vfpOk = mismatch && ViaFabricPlusCompat.canTranslate(gameVer);
            boolean fav = cfg != null && cfg.isFavorite(inv.host());

            String worldName = (inv.worldName() == null || inv.worldName().isBlank())
                    ? (inv.hostName() + "'s World")
                    : inv.worldName();
            String sourceDesc = inv.invitedByName() != null && !inv.invitedByName().isBlank()
                    && !inv.invitedByName().equalsIgnoreCase(inv.hostName())
                    ? "Invited by " + inv.invitedByName()
                    : "Invited you";
            String badge = mismatch ? "MC " + gameVer : "Invited";
            int badgeColor = mismatch ? (vfpOk ? BurrowUi.ROLE_VISITOR : BurrowUi.DOT_LOCKED) : BurrowUi.DOT_NOTIFY;

            invitedList.add(new JoinableWorld(
                    inv.host(), inv.hostName(), worldName,
                    badge, badgeColor, sourceDesc,
                    pCount, pMax, mismatch, vfpOk, gameVer, fav));
        }

        // 2. Friends currently hosting unlocked joinable worlds
        for (FriendsState.Friend f : state.friends()) {
            if (f.uuid() == null || !f.isOnline() || !f.hosting() || f.locked()) continue;
            if (!seen.add(f.uuid())) continue;

            boolean mismatch = f.isVersionMismatch();
            boolean vfpOk = mismatch && ViaFabricPlusCompat.canTranslate(f.gameVersion());
            boolean fav = cfg != null && cfg.isFavorite(f.uuid());
            String worldName = (f.worldName() == null || f.worldName().isBlank())
                    ? (f.name() + "'s World")
                    : f.worldName();
            String badge = mismatch ? "MC " + f.gameVersion() : "Friend";
            int badgeColor = mismatch ? (vfpOk ? BurrowUi.ROLE_VISITOR : BurrowUi.DOT_LOCKED) : BurrowUi.ROLE_MEMBER;

            friendsList.add(new JoinableWorld(
                    f.uuid(), f.name(), worldName,
                    badge, badgeColor, "Friend's World",
                    f.playerCount(), f.maxPlayers(), mismatch, vfpOk, f.gameVersion(), fav));
        }

        // 3. Discoverable worlds (Friends of Friends or server-known Invite)
        List<FriendsState.DiscoverableWorld> discoverable = new ArrayList<>(state.discoverableWorlds());
        discoverable.sort((a, b) -> Integer.compare(sourcePriority(a), sourcePriority(b)));
        for (FriendsState.DiscoverableWorld w : discoverable) {
            if (w.host() == null || w.locked() || !seen.add(w.host())) continue;

            boolean mismatch = w.isVersionMismatch();
            boolean vfpOk = mismatch && ViaFabricPlusCompat.canTranslate(w.gameVersion());
            boolean fav = (cfg != null && cfg.isFavorite(w.host()))
                    || (!w.viaName().isEmpty() && favNamesLower.contains(w.viaName().toLowerCase(Locale.ROOT)));
            String worldName = (w.worldName() == null || w.worldName().isBlank())
                    ? (w.hostName() + "'s World")
                    : w.worldName();

            String badge;
            int badgeColor;
            String sourceDesc;
            if ("invite".equals(w.source())) {
                badge = "Invited";
                badgeColor = BurrowUi.DOT_NOTIFY;
                sourceDesc = w.viaName().isEmpty() ? "Invited" : "Invited by " + w.viaName();
            } else if ("friend_of_friend".equals(w.source()) && !w.viaName().isEmpty()) {
                badge = "via " + w.viaName();
                badgeColor = BurrowUi.ROLE_VISITOR;
                sourceDesc = "Friend of " + w.viaName();
            } else {
                badge = "Burrow";
                badgeColor = BurrowUi.ROLE_HOST;
                sourceDesc = "Burrow World";
            }
            if (mismatch) {
                badge = "MC " + w.gameVersion();
                badgeColor = vfpOk ? BurrowUi.ROLE_VISITOR : BurrowUi.DOT_LOCKED;
            }

            discoverableList.add(new JoinableWorld(
                    w.host(), w.hostName(), worldName,
                    badge, badgeColor, sourceDesc,
                    w.playerCount(), w.maxPlayers(), mismatch, vfpOk, w.gameVersion(), fav));
        }

        Comparator<JoinableWorld> favFirst = Comparator
                .comparing((JoinableWorld w) -> !w.favorite())
                .thenComparing(w -> w.hostName().toLowerCase(Locale.ROOT));
        invitedList.sort(favFirst);
        friendsList.sort(favFirst);
        discoverableList.sort(favFirst);

        List<JoinableWorld> result = new ArrayList<>(invitedList.size() + friendsList.size() + discoverableList.size());
        result.addAll(invitedList);
        result.addAll(friendsList);
        result.addAll(discoverableList);
        return result;
    }

    private static int sourcePriority(FriendsState.DiscoverableWorld w) {
        if ("invite".equals(w.source())) return 0;
        if ("friend_of_friend".equals(w.source())) return 1;
        return 2;
    }

    /** Normalize a pasted join code (handles full `<CODE>.burrow.meeyah.dev` or plain 6-char code). */
    public static String normalizeCodeInput(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int dot = s.indexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        StringBuilder out = new StringBuilder(6);
        for (int i = 0; i < s.length() && out.length() < 6; i++) {
            char c = Character.toUpperCase(s.charAt(i));
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Non-selectable section header separating Burrow worlds at the bottom of the Multiplayer list. */
    public static final class HeaderEntry extends ServerSelectionList.Entry {
        private static final Component LABEL = Component.literal("Burrow Worlds");

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partialTick) {
            drawDividerHeader(g, LABEL, BurrowUi.DOT_LIVE,
                    getContentX(), getContentRight(), getContentXMiddle(), getContentYMiddle());
        }

        @Override
        public Component getNarration() {
            return LABEL;
        }

        @Override
        protected boolean matches(ServerSelectionList.Entry other) {
            return other instanceof HeaderEntry;
        }

        @Override
        public void join() {}
    }

    /** Non-selectable section header for the "Join by Code" bar at the very bottom of the Multiplayer list. */
    public static final class CodeHeaderEntry extends ServerSelectionList.Entry {
        private static final Component LABEL = Component.literal("Join by Burrow Code");

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partialTick) {
            drawDividerHeader(g, LABEL, BurrowUi.DOT_NOTIFY,
                    getContentX(), getContentRight(), getContentXMiddle(), getContentYMiddle());
        }

        @Override
        public Component getNarration() {
            return LABEL;
        }

        @Override
        protected boolean matches(ServerSelectionList.Entry other) {
            return other instanceof CodeHeaderEntry;
        }

        @Override
        public void join() {}
    }

    private static void drawDividerHeader(
            GuiGraphicsExtractor g,
            Component label,
            int dotColor,
            int contentX,
            int contentRight,
            int midX,
            int midY
    ) {
        Font font = Minecraft.getInstance().font;
        int labelW = font.width(label);
        int totalW = labelW + 10;
        int startX = midX - totalW / 2;

        int lineY = midY;
        int leftEnd = startX - 8;
        int rightStart = startX + totalW + 8;
        if (leftEnd > contentX + 6) {
            g.fill(contentX + 6, lineY, leftEnd, lineY + 1, 0x44FFFFFF);
        }
        if (rightStart < contentRight - 6) {
            g.fill(rightStart, lineY, contentRight - 6, lineY + 1, 0x44FFFFFF);
        }

        g.fill(startX, midY - 2, startX + 4, midY + 2, dotColor);
        g.text(font, label, startX + 8, midY - 4, 0xFFE0E0E8, true);
    }

    /**
     * Interactive Join Code text box at the very bottom of the Multiplayer list.
     * Typing or pasting a 6-character code immediately looks up the world and
     * renders a live {@link CodePreviewEntry} right below.
     */
    public static final class CodeInputEntry extends ServerSelectionList.Entry {
        private final EditBox codeBox;
        private final Button clearBtn;
        private String lastLookedUpCode = "";
        private boolean updatingBox = false;

        public CodeInputEntry() {
            Font font = Minecraft.getInstance().font;
            this.codeBox = new EditBox(font, 0, 0, 128, 20, Component.literal("Burrow join code"));
            this.codeBox.setMaxLength(48); // allow pasting "<CODE>.burrow.meeyah.dev" and auto-trimming
            this.codeBox.setHint(Component.literal("Paste 6-char code..."));
            this.codeBox.setResponder(this::onTextChanged);

            this.clearBtn = Button.builder(Component.literal("Clear"), b -> {
                this.codeBox.setValue("");
                this.lastLookedUpCode = "";
                BurrowClient.friends().clearCodePreview();
            }).bounds(0, 0, 44, 20).build();
        }

        private void onTextChanged(String raw) {
            if (this.updatingBox) return;
            String normalized = normalizeCodeInput(raw);
            if (!normalized.equals(raw)) {
                this.updatingBox = true;
                this.codeBox.setValue(normalized);
                this.updatingBox = false;
            }
            if (normalized.length() == 6) {
                if (!normalized.equals(this.lastLookedUpCode)) {
                    this.lastLookedUpCode = normalized;
                    BurrowConnection conn = BurrowClient.connection();
                    if (conn != null && conn.status() == BurrowConnection.Status.CONNECTED) {
                        conn.codeLookup(normalized);
                    } else {
                        BurrowClient.friends().setCodePreview(new FriendsState.CodePreview(
                                normalized, false, null, "", "", "", 0, 0, false, false,
                                "Not connected to Burrow server"));
                    }
                }
            } else {
                this.lastLookedUpCode = "";
                BurrowClient.friends().clearCodePreview();
            }
        }

        public boolean isBoxFocused() {
            return this.codeBox.isFocused();
        }

        public void focusBox() {
            this.codeBox.setFocused(true);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int x = getContentX();
            int y = getContentY();
            int w = getContentWidth();

            int rowY = y + 6;
            g.text(font, "Join Code:", x + 4, rowY + 6, 0xFFD0D0DA, true);

            int boxX = x + 64;
            int clearW = 44;
            int boxW = Math.max(96, Math.min(136, w - 64 - clearW - 14));
            this.codeBox.setX(boxX);
            this.codeBox.setY(rowY);
            this.codeBox.setWidth(boxW);
            this.codeBox.extractRenderState(g, mouseX, mouseY, partialTick);

            String current = this.codeBox.getValue();
            if (!current.isEmpty()) {
                this.clearBtn.setX(boxX + boxW + 6);
                this.clearBtn.setY(rowY);
                this.clearBtn.extractRenderState(g, mouseX, mouseY, partialTick);
            } else {
                int hintX = boxX + boxW + 8;
                int maxHintW = Math.max(0, x + w - hintX - 4);
                if (maxHintW > 30) {
                    g.text(font, BurrowFriendsScreen.ellipsize(font, "A-Z / 0-9", maxHintW),
                            hintX, rowY + 6, BurrowUi.TEXT_MUTED, false);
                }
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (!this.codeBox.getValue().isEmpty() && this.clearBtn.mouseClicked(event, doubleClick)) {
                return true;
            }
            if (this.codeBox.isMouseOver(event.x(), event.y())) {
                this.codeBox.setFocused(true);
                this.codeBox.mouseClicked(event, doubleClick);
                return true;
            }
            this.codeBox.setFocused(false);
            return false;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (!this.codeBox.isFocused()) {
                return false;
            }
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                this.codeBox.setFocused(false);
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                FriendsState.CodePreview preview = BurrowClient.friends().codePreview();
                if (preview != null && preview.found() && !preview.locked() && !preview.isSelf()
                        && (!preview.isVersionMismatch() || ViaFabricPlusCompat.canTranslate(preview.gameVersion()))) {
                    JoinFlow.beginByCode(preview.code());
                    return true;
                }
                String code = normalizeCodeInput(this.codeBox.getValue());
                if (code.length() == 6 && BurrowClient.connection() != null) {
                    this.lastLookedUpCode = code;
                    BurrowClient.connection().codeLookup(code);
                    return true;
                }
            }
            return this.codeBox.keyPressed(event);
        }

        @Override
        public boolean charTyped(CharacterEvent event) {
            if (!this.codeBox.isFocused()) {
                return false;
            }
            return this.codeBox.charTyped(event);
        }

        @Override
        public void setFocused(boolean focused) {
            super.setFocused(focused);
            if (!focused) {
                this.codeBox.setFocused(false);
            }
        }

        @Override
        public Component getNarration() {
            return Component.literal("Join by Burrow Code");
        }

        @Override
        protected boolean matches(ServerSelectionList.Entry other) {
            return other instanceof CodeInputEntry;
        }

        @Override
        public void join() {}
    }

    /** Live preview card displayed directly below {@link CodeInputEntry} once a 6-char code is looked up. */
    public static final class CodePreviewEntry extends ServerSelectionList.Entry {
        private final FriendsState.CodePreview preview;
        private final boolean vfpOk;
        private final boolean canJoin;
        private final Button joinBtn;

        public CodePreviewEntry(FriendsState.CodePreview preview) {
            this.preview = preview;
            this.vfpOk = preview.found() && preview.isVersionMismatch()
                    && ViaFabricPlusCompat.canTranslate(preview.gameVersion());
            this.canJoin = preview.found()
                    && !preview.locked()
                    && !preview.isSelf()
                    && (!preview.isVersionMismatch() || this.vfpOk);

            String btnLabel;
            int btnColor;
            if (!preview.found()) {
                btnLabel = "";
                btnColor = BurrowUi.TEXT_MUTED;
            } else if (preview.isSelf()) {
                btnLabel = "Yours";
                btnColor = BurrowUi.TEXT_MUTED;
            } else if (preview.locked()) {
                btnLabel = "Locked";
                btnColor = BurrowUi.DOT_LOCKED;
            } else if (preview.isVersionMismatch() && !this.vfpOk) {
                btnLabel = "MC " + preview.gameVersion();
                btnColor = BurrowUi.DOT_LOCKED;
            } else {
                btnLabel = "Join";
                btnColor = 0xFF7AE89B;
            }
            this.joinBtn = Button.builder(Component.literal(btnLabel).withColor(btnColor), b -> join())
                    .bounds(0, 0, 56, 20).build();
            this.joinBtn.active = this.canJoin;
        }

        public boolean canJoin() {
            return this.canJoin;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int x = getContentX();
            int y = getContentY();
            int w = getContentWidth();

            if (!this.preview.found()) {
                g.fill(x, y + 3, x + w, y + 29, 0xCC1C1C22);
                g.outline(x, y + 3, w, 26, 0xFF3A282C);
                String err = (this.preview.error() == null || this.preview.error().isBlank())
                        ? "No world found for code " + this.preview.code()
                        : this.preview.error();
                g.centeredText(font, BurrowFriendsScreen.ellipsize(font, err, w - 16),
                        x + w / 2, y + 12, 0xFFFF9977);
                return;
            }

            // 32x32 host skin head on the left
            PlayerHeads.draw(g, this.preview.host(), this.preview.hostName(), x, y, 32);

            boolean hoveringIcon = hovered && mouseX >= x && mouseX < x + 32 && mouseY >= y && mouseY < y + 32;
            if (hovered && this.canJoin) {
                g.fill(x, y, x + 32, y + 32, 0x80909090);
                Identifier sprite = hoveringIcon ? JOIN_HIGHLIGHTED_SPRITE : JOIN_SPRITE;
                g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, 32, 32);
            }

            // Right-aligned Join button + player count
            int btnX = x + w - 58;
            this.joinBtn.setX(btnX);
            this.joinBtn.setY(y + 6);
            this.joinBtn.extractRenderState(g, mouseX, mouseY, partialTick);

            int rightCursor = btnX - 6;
            if (this.preview.maxPlayers() > 0) {
                String countText = this.preview.playerCount() + "/" + this.preview.maxPlayers();
                int countW = font.width(countText);
                g.text(font, countText, rightCursor - countW, y + 1, 0xFFAAAAAA, false);
                rightCursor -= countW + 6;
            }

            int textX = x + 35;
            String badge = this.preview.isVersionMismatch() ? "MC " + this.preview.gameVersion() : "Code";
            int badgeColor = this.preview.isVersionMismatch()
                    ? (this.vfpOk ? BurrowUi.ROLE_VISITOR : BurrowUi.DOT_LOCKED)
                    : BurrowUi.DOT_NOTIFY;
            int badgeW = font.width(badge) + 6;
            int maxTitleW = Math.max(40, rightCursor - textX - badgeW - 6);
            String worldName = (this.preview.worldName() == null || this.preview.worldName().isBlank())
                    ? (this.preview.hostName() + "'s World")
                    : this.preview.worldName();
            String title = BurrowFriendsScreen.ellipsize(font, worldName, maxTitleW);
            g.text(font, title, textX, y + 1, BurrowUi.TEXT_PRIMARY, true);
            BurrowUi.drawTagPill(g, font, textX + font.width(title) + 5, y, badge, badgeColor);

            int maxLineW = Math.max(50, btnX - textX - 6);
            g.text(font,
                    BurrowFriendsScreen.ellipsize(font, "Hosted by " + this.preview.hostName(), maxLineW),
                    textX, y + 12, 0xFFA0A0A0, false);

            String line3;
            int line3Color;
            if (this.preview.isSelf()) {
                line3 = "This is your own hosted world";
                line3Color = BurrowUi.TEXT_MUTED;
            } else if (this.preview.locked()) {
                line3 = "World is currently locked";
                line3Color = BurrowUi.DOT_LOCKED;
            } else if (this.preview.isVersionMismatch() && !this.vfpOk) {
                line3 = "Incompatible version (MC " + this.preview.gameVersion() + ")";
                line3Color = BurrowUi.DOT_LOCKED;
            } else if (this.vfpOk) {
                line3 = "Code " + this.preview.code() + " · ViaFabricPlus (MC " + this.preview.gameVersion() + ")";
                line3Color = BurrowUi.ROLE_VISITOR;
            } else {
                line3 = "Code " + this.preview.code() + " · Click Join to connect";
                line3Color = 0xFF88AA98;
            }
            g.text(font, BurrowFriendsScreen.ellipsize(font, line3, maxLineW), textX, y + 23, line3Color, false);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (this.preview.found() && this.joinBtn.mouseClicked(event, doubleClick)) {
                return true;
            }
            if (this.canJoin) {
                double relX = event.x() - getContentX();
                if (doubleClick || (relX >= 0 && relX < 32)) {
                    join();
                    return true;
                }
            }
            return super.mouseClicked(event, doubleClick);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.isSelection() && this.canJoin) {
                join();
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public void join() {
            if (this.canJoin) {
                JoinFlow.beginByCode(this.preview.code());
            }
        }

        @Override
        public Component getNarration() {
            return Component.literal(this.preview.found()
                    ? this.preview.worldName() + " hosted by " + this.preview.hostName()
                    : this.preview.error());
        }

        @Override
        protected boolean matches(ServerSelectionList.Entry other) {
            return other instanceof CodePreviewEntry o
                    && this.preview.code().equals(o.preview.code());
        }
    }

    /** Vanilla-native Multiplayer list row for a joinable Burrow world. */
    public static final class WorldEntry extends ServerSelectionList.Entry {
        private final JoinableWorld world;

        public WorldEntry(JoinableWorld world) {
            this.world = world;
        }

        public JoinableWorld world() {
            return this.world;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int x = getContentX();
            int y = getContentY();
            int w = getContentWidth();

            // 32x32 host skin head on the left (matches vanilla server icon dimensions)
            PlayerHeads.draw(g, this.world.host(), this.world.hostName(), x, y, 32);

            boolean hoveringIcon = hovered && mouseX >= x && mouseX < x + 32 && mouseY >= y && mouseY < y + 32;
            if (hovered && this.world.canJoin()) {
                g.fill(x, y, x + 32, y + 32, 0x80909090);
                Identifier sprite = hoveringIcon ? JOIN_HIGHLIGHTED_SPRITE : JOIN_SPRITE;
                g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, 32, 32);
            }

            // Right-side status icon + player count (aligned like OnlineServerEntry)
            int rightCursor = x + w - 5;
            if (!this.world.canJoin()) {
                g.blitSprite(RenderPipelines.GUI_TEXTURED, INCOMPATIBLE_SPRITE, rightCursor - 10, y + 1, 10, 8);
                rightCursor -= 14;
            } else {
                g.fill(rightCursor - 5, y + 3, rightCursor - 1, y + 7, BurrowUi.DOT_LIVE);
                rightCursor -= 9;
            }
            if (this.world.maxPlayers() > 0) {
                String countText = this.world.playerCount() + "/" + this.world.maxPlayers();
                int countW = font.width(countText);
                g.text(font, countText, rightCursor - countW, y + 1, 0xFFAAAAAA, false);
                rightCursor -= countW + 6;
            }
            if (this.world.favorite()) {
                BurrowUi.drawStar(g, rightCursor - 10, y + 1, true, false);
                rightCursor -= 14;
            }

            int textX = x + 35;
            int badgeW = font.width(this.world.badge()) + 6;
            int maxTitleW = Math.max(40, rightCursor - textX - badgeW - 6);
            String title = BurrowFriendsScreen.ellipsize(font, this.world.worldName(), maxTitleW);
            g.text(font, title, textX, y + 1, BurrowUi.TEXT_PRIMARY, true);
            BurrowUi.drawTagPill(g, font, textX + font.width(title) + 5, y, this.world.badge(), this.world.badgeColor());

            int maxLineW = Math.max(60, w - 40);
            g.text(font,
                    BurrowFriendsScreen.ellipsize(font, "Hosted by " + this.world.hostName(), maxLineW),
                    textX, y + 12, 0xFFA0A0A0, false);

            String line3;
            int line3Color;
            if (this.world.versionMismatch() && !this.world.vfpCompatible()) {
                line3 = "Incompatible version (MC " + this.world.gameVersion() + ")";
                line3Color = BurrowUi.DOT_LOCKED;
            } else if (this.world.vfpCompatible()) {
                line3 = "Burrow · " + this.world.sourceDesc() + " · ViaFabricPlus";
                line3Color = BurrowUi.ROLE_VISITOR;
            } else {
                line3 = "Burrow · " + this.world.sourceDesc();
                line3Color = 0xFF808080;
            }
            g.text(font, BurrowFriendsScreen.ellipsize(font, line3, maxLineW), textX, y + 23, line3Color, false);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (this.world.canJoin()) {
                double relX = event.x() - getContentX();
                if (doubleClick || (relX >= 0 && relX < 32)) {
                    join();
                    return true;
                }
            }
            return super.mouseClicked(event, doubleClick);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.isSelection() && this.world.canJoin()) {
                join();
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public void join() {
            if (this.world.canJoin()) {
                JoinFlow.beginByHostUuid(this.world.host());
            }
        }

        @Override
        public Component getNarration() {
            return Component.literal(this.world.worldName() + " hosted by " + this.world.hostName());
        }

        @Override
        protected boolean matches(ServerSelectionList.Entry other) {
            return other instanceof WorldEntry w && this.world.host().equals(w.world.host());
        }
    }
}
