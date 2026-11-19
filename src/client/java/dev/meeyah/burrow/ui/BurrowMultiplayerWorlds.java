package dev.meeyah.burrow.ui;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.session.JoinFlow;
import dev.meeyah.burrow.state.FriendsState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Renders joinable Burrow worlds (Invited, Friends, Friends of Friends, and
 * Open worlds) at the bottom of Minecraft's vanilla Multiplayer server list.
 */
public final class BurrowMultiplayerWorlds {
    private static final Identifier JOIN_SPRITE = Identifier.withDefaultNamespace("server_list/join");
    private static final Identifier JOIN_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("server_list/join_highlighted");
    private static final Identifier INCOMPATIBLE_SPRITE = Identifier.withDefaultNamespace("server_list/incompatible");

    private BurrowMultiplayerWorlds() {}

    /** Duck interface implemented by {@code ServerSelectionListMixin}. */
    public interface BurrowServerListAccess {
        void burrow$refreshBurrowWorlds();
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
            String gameVersion
    ) {}

    /** Builds fresh ServerSelectionList entries for all currently joinable Burrow worlds. */
    public static List<ServerSelectionList.Entry> buildEntries() {
        List<JoinableWorld> worlds = collectJoinableWorlds();
        List<ServerSelectionList.Entry> out = new ArrayList<>(worlds.size());
        for (JoinableWorld w : worlds) {
            out.add(new WorldEntry(w));
        }
        return out;
    }

    /**
     * Collects all currently joinable Burrow worlds deduplicated by host UUID,
     * ordered: Invited -> Friends -> Friends of Friends -> Open.
     */
    public static List<JoinableWorld> collectJoinableWorlds() {
        FriendsState state = BurrowClient.friends();
        state.pruneExpired();

        Map<UUID, FriendsState.Friend> friendByUuid = new HashMap<>();
        for (FriendsState.Friend f : state.friends()) {
            if (f.uuid() != null) {
                friendByUuid.put(f.uuid(), f);
            }
        }
        Map<UUID, FriendsState.DiscoverableWorld> discByUuid = new HashMap<>();
        for (FriendsState.DiscoverableWorld d : state.discoverableWorlds()) {
            if (d.host() != null) {
                discByUuid.put(d.host(), d);
            }
        }

        List<JoinableWorld> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();

        // 1. Active incoming world invites (works even for Invite Only worlds)
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

            String worldName = (inv.worldName() == null || inv.worldName().isBlank())
                    ? (inv.hostName() + "'s World")
                    : inv.worldName();
            String sourceDesc = inv.invitedByName() != null && !inv.invitedByName().isBlank()
                    && !inv.invitedByName().equalsIgnoreCase(inv.hostName())
                    ? "Invited by " + inv.invitedByName()
                    : "Invited you";
            String badge = mismatch ? "MC " + gameVer : "Invited";
            int badgeColor = mismatch ? BurrowUi.DOT_LOCKED : BurrowUi.DOT_NOTIFY;

            result.add(new JoinableWorld(
                    inv.host(), inv.hostName(), worldName,
                    badge, badgeColor, sourceDesc,
                    pCount, pMax, mismatch, gameVer));
        }

        // 2. Friends currently hosting unlocked joinable worlds
        for (FriendsState.Friend f : state.friends()) {
            if (f.uuid() == null || !f.isOnline() || !f.hosting() || f.locked()) continue;
            if (!seen.add(f.uuid())) continue;

            boolean mismatch = f.isVersionMismatch();
            String worldName = (f.worldName() == null || f.worldName().isBlank())
                    ? (f.name() + "'s World")
                    : f.worldName();
            String badge = mismatch ? "MC " + f.gameVersion() : "Friend";
            int badgeColor = mismatch ? BurrowUi.DOT_LOCKED : BurrowUi.ROLE_MEMBER;

            result.add(new JoinableWorld(
                    f.uuid(), f.name(), worldName,
                    badge, badgeColor, "Friend's World",
                    f.playerCount(), f.maxPlayers(), mismatch, f.gameVersion()));
        }

        // 3. Discoverable worlds (Friends of Friends, Open, or server-known Invite)
        List<FriendsState.DiscoverableWorld> discoverable = new ArrayList<>(state.discoverableWorlds());
        discoverable.sort((a, b) -> Integer.compare(sourcePriority(a), sourcePriority(b)));
        for (FriendsState.DiscoverableWorld w : discoverable) {
            if (w.host() == null || w.locked() || !seen.add(w.host())) continue;

            boolean mismatch = w.isVersionMismatch();
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
            } else if ("open".equals(w.access())) {
                badge = "Network";
                badgeColor = BurrowUi.ROLE_MEMBER;
                sourceDesc = "Friend Network";
            } else {
                badge = "Burrow";
                badgeColor = BurrowUi.ROLE_HOST;
                sourceDesc = "Burrow World";
            }
            if (mismatch) {
                badge = "MC " + w.gameVersion();
                badgeColor = BurrowUi.DOT_LOCKED;
            }

            result.add(new JoinableWorld(
                    w.host(), w.hostName(), worldName,
                    badge, badgeColor, sourceDesc,
                    w.playerCount(), w.maxPlayers(), mismatch, w.gameVersion()));
        }

        return result;
    }

    private static int sourcePriority(FriendsState.DiscoverableWorld w) {
        if ("invite".equals(w.source())) return 0;
        if ("friend_of_friend".equals(w.source())) return 1;
        return 2;
    }

    /** Non-selectable section header separating Burrow worlds at the bottom of the Multiplayer list. */
    public static final class HeaderEntry extends ServerSelectionList.Entry {
        private static final Component LABEL = Component.literal("Burrow Worlds");

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int midX = getContentXMiddle();
            int midY = getContentYMiddle();
            int labelW = font.width(LABEL);
            int totalW = labelW + 10;
            int startX = midX - totalW / 2;

            // Subtle vanilla divider lines flanking the centered header label
            int lineY = midY;
            int leftEnd = startX - 8;
            int rightStart = startX + totalW + 8;
            if (leftEnd > getContentX() + 6) {
                g.fill(getContentX() + 6, lineY, leftEnd, lineY + 1, 0x44FFFFFF);
            }
            if (rightStart < getContentRight() - 6) {
                g.fill(rightStart, lineY, getContentRight() - 6, lineY + 1, 0x44FFFFFF);
            }

            g.fill(startX, midY - 2, startX + 4, midY + 2, BurrowUi.DOT_LIVE);
            g.text(font, LABEL, startX + 8, midY - 4, 0xFFE0E0E8, true);
        }

        @Override
        public Component getNarration() {
            return LABEL;
        }

        @Override
        public boolean matches(ServerSelectionList.Entry other) {
            return other instanceof HeaderEntry;
        }

        @Override
        public void join() {
            // Section header is not joinable.
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
            if (hovered && !this.world.versionMismatch()) {
                g.fill(x, y, x + 32, y + 32, 0x80909090);
                Identifier sprite = hoveringIcon ? JOIN_HIGHLIGHTED_SPRITE : JOIN_SPRITE;
                g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, 32, 32);
            }

            // Right-side status icon + player count (aligned like OnlineServerEntry)
            int rightCursor = x + w - 5;
            if (this.world.versionMismatch()) {
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

            String line3 = this.world.versionMismatch()
                    ? "Incompatible version (MC " + this.world.gameVersion() + ")"
                    : "Burrow · " + this.world.sourceDesc();
            int line3Color = this.world.versionMismatch() ? BurrowUi.DOT_LOCKED : 0xFF808080;
            g.text(font, BurrowFriendsScreen.ellipsize(font, line3, maxLineW), textX, y + 23, line3Color, false);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (!this.world.versionMismatch()) {
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
            if (event.isSelection() && !this.world.versionMismatch()) {
                join();
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public void join() {
            if (!this.world.versionMismatch()) {
                JoinFlow.beginByHostUuid(this.world.host());
            }
        }

        @Override
        public Component getNarration() {
            return Component.literal(this.world.worldName() + " hosted by " + this.world.hostName());
        }

        @Override
        public boolean matches(ServerSelectionList.Entry other) {
            return other instanceof WorldEntry w && this.world.host().equals(w.world.host());
        }
    }
}
