package dev.meeyah.burrow.ui;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.config.BurrowConfig;
import dev.meeyah.burrow.net.BurrowConnection;
import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.session.JoinFlow;
import dev.meeyah.burrow.state.ActivityTracker;
import dev.meeyah.burrow.state.FriendsState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Burrow's vanilla-styled Friends modal.
 *
 * Two clean tabs (Friends + discoverable Worlds), compact eye-icon Incognito
 * toggle, blue notification dots & inline drawers for invites / invite
 * requests / joining / inviting (with automatic host-start), and a dedicated
 * Add Friend & Requests modal in the footer.
 */
public class BurrowFriendsScreen extends Screen {
    private static final int PANEL_W = BurrowUi.PANEL_W;
    private static final int PANEL_H = BurrowUi.PANEL_H;
    private static final int PAD_X = BurrowUi.PAD;
    private static final int TABS_Y = BurrowUi.TABS_Y;
    private static final int TAB_H = BurrowUi.TAB_H;
    private static final int CONTENT_Y = BurrowUi.CONTENT_Y;
    private static final int CONTENT_H = BurrowUi.CONTENT_H;
    private static final int FOOTER_Y = BurrowUi.FOOTER_Y;

    private enum Tab { FRIENDS, WORLDS }

    private final @Nullable Screen parent;
    private final FriendsState.Listener listener = this::onFriendsChanged;
    private Tab tab = Tab.FRIENDS;
    private @Nullable UUID expandedFriend = null;
    private int page = 0;
    private boolean showingErrorFlash = false;

    private int panelX;
    private int panelY;
    private int contentX;
    private int contentY;
    private int contentW;
    private final List<CardDisplay> worldCards = new ArrayList<>();
    private @Nullable String emptyMessage;

    private record CardDisplay(
            UUID uuid,
            String name,
            String subtitle,
            @Nullable String badge,
            int badgeColor,
            int x, int y, int w, int h
    ) {}

    public BurrowFriendsScreen(@Nullable Screen parent) {
        super(Component.literal("Friends"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.worldCards.clear();
        this.emptyMessage = null;

        this.panelX = (this.width - PANEL_W) / 2;
        this.panelY = Math.max(4, (this.height - PANEL_H) / 2);
        this.contentX = this.panelX + PAD_X;
        this.contentY = this.panelY + CONTENT_Y;
        this.contentW = PANEL_W - PAD_X * 2;

        Minecraft mc = this.minecraft;
        BurrowConfig cfg = BurrowClient.config();

        // ----- Compact 20x20 Eye-Icon Incognito Toggle (top-right) -----
        boolean incognito = cfg != null && cfg.incognito;
        addRenderableWidget(new BurrowUi.IncognitoButton(
                this.contentX + this.contentW - 20,
                this.panelY + BurrowUi.HEADER_TITLE_Y,
                incognito,
                () -> {
                    if (cfg != null) {
                        cfg.incognito = !cfg.incognito;
                        cfg.save();
                        ActivityTracker.pushCurrent();
                        rebuildWidgets();
                    }
                }));

        // ----- Tab Counts -----
        List<FriendsState.Friend> friends = sortedFriends();
        List<WorldEntry> worlds = collectDiscoverableWorlds();

        // ----- 2 Spacious Vanilla Tabs (flush with inset box) -----
        int tabX = this.contentX;
        int tabY = this.panelY + TABS_Y;
        int tabGap = 2;
        int tabW = (this.contentW - tabGap) / 2;
        int rightTabW = this.contentW - tabW - tabGap;

        addRenderableWidget(new BurrowUi.TabButton(
                tabX, tabY, tabW, TAB_H,
                Component.literal("Friends (" + friends.size() + ")"),
                this.tab == Tab.FRIENDS,
                () -> switchTab(Tab.FRIENDS)));
        addRenderableWidget(new BurrowUi.TabButton(
                tabX + tabW + tabGap, tabY, rightTabW, TAB_H,
                Component.literal(worlds.isEmpty() ? "Worlds" : "Worlds (" + worlds.size() + ")"),
                this.tab == Tab.WORLDS,
                () -> switchTab(Tab.WORLDS)));

        // ----- Active Tab Body -----
        switch (this.tab) {
            case FRIENDS -> initFriendsTab(friends);
            case WORLDS -> initWorldsTab(worlds);
        }

        // ----- Footer Bar -----
        int footerY = this.panelY + FOOTER_Y;
        int incomingCount = 0;
        for (FriendsState.Request r : BurrowClient.friends().requests()) {
            if (!r.outgoing()) incomingCount++;
        }
        Component reqLabel = incomingCount > 0
                ? Component.literal("Add / Requests (" + incomingCount + ")").withColor(BurrowUi.DOT_NOTIFY)
                : Component.literal("Add / Requests");
        int reqBtnW = incomingCount > 0 ? 116 : 96;
        addRenderableWidget(Button.builder(reqLabel, b -> {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(new FriendRequestsScreen(this));
            }
        }).bounds(this.contentX, footerY, reqBtnW, 20).build());

        if (mc.getSingleplayerServer() != null) {
            boolean hosting = HostSession.isHosting();
            addRenderableWidget(Button.builder(
                            Component.literal(hosting ? "Host Settings" : "Host World"),
                            b -> mc.gui.setScreen(new HostWorldScreen(this)))
                    .bounds(this.contentX + reqBtnW + 4, footerY, 92, 20).build());
        } else {
            HostSession.ensureStopped();
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(this.contentX + this.contentW - 84, footerY, 84, 20).build());
    }

    private void initFriendsTab(List<FriendsState.Friend> friends) {
        if (this.expandedFriend != null) {
            boolean exists = friends.stream().anyMatch(f -> this.expandedFriend.equals(f.uuid()));
            if (!exists) {
                this.expandedFriend = null;
            }
        }
        if (friends.isEmpty()) {
            this.emptyMessage = "No friends yet — click Add / Requests below.";
            return;
        }

        int innerX = this.contentX + 8;
        int innerW = this.contentW - 16;
        int perPage = (this.expandedFriend != null) ? 2 : 3;
        int pages = Math.max(1, (friends.size() + perPage - 1) / perPage);
        if (this.page >= pages) this.page = 0;
        int from = this.page * perPage;
        int to = Math.min(friends.size(), from + perPage);

        int y = this.contentY + 8;
        for (FriendsState.Friend f : friends.subList(from, to)) {
            boolean expanded = f.uuid() != null && f.uuid().equals(this.expandedFriend);
            FriendsState.WorldInvite worldInv = BurrowClient.friends().worldInviteFrom(f.uuid());
            boolean requestedInvite = BurrowClient.friends().hasInviteRequestFrom(f.uuid());
            addRenderableWidget(new FriendCardWidget(
                    innerX, y, innerW, 30, f, worldInv, requestedInvite, expanded,
                    () -> {
                        this.expandedFriend = expanded ? null : f.uuid();
                        rebuildWidgets();
                    }));
            y += 30;
            if (expanded) {
                addFriendDrawer(f, worldInv, requestedInvite, innerX, y, innerW);
                y += 25;
            }
            y += 4;
        }

        if (pages > 1) {
            addPager(innerX, this.contentY + CONTENT_H - 20, innerW, pages);
        }
    }

    private void addFriendDrawer(
            FriendsState.Friend f,
            @Nullable FriendsState.WorldInvite worldInv,
            boolean requestedInvite,
            int x,
            int y,
            int w
    ) {
        int btnY = y + 3;
        int curX = x + 6;
        int gap = 4;
        boolean inSingleplayer = this.minecraft != null && this.minecraft.getSingleplayerServer() != null;

        // 1. Incoming world invite from this friend -> Accept Invite + Decline
        if (worldInv != null) {
            addRenderableWidget(Button.builder(
                            Component.literal("Accept Invite").withColor(0xFF7AE89B),
                            b -> JoinFlow.beginByHostUuid(worldInv.host()))
                    .bounds(curX, btnY, 86, 18).build());
            curX += 86 + gap;

            addRenderableWidget(Button.builder(Component.literal("Decline"), b -> {
                BurrowClient.friends().dismissInvite(worldInv.host());
                rebuildWidgets();
            }).bounds(curX, btnY, 54, 18).build());
            curX += 54 + gap;
        }
        // 2. Incoming invite request from this friend -> Invite to World (auto-hosts!) + Dismiss
        else if (requestedInvite) {
            if (inSingleplayer) {
                addRenderableWidget(Button.builder(
                                Component.literal("Invite to World").withColor(0xFF7AE89B),
                                b -> {
                                    HostSession.inviteFriend(f.uuid());
                                    rebuildWidgets();
                                })
                        .bounds(curX, btnY, 96, 18).build());
                curX += 96 + gap;
            }
            addRenderableWidget(Button.builder(Component.literal("Dismiss"), b -> {
                BurrowClient.friends().dismissInviteRequest(f.uuid());
                rebuildWidgets();
            }).bounds(curX, btnY, 54, 18).build());
            curX += 54 + gap;
        }
        // 3. Normal context actions
        else {
            if (f.hosting() && !f.locked()) {
                addRenderableWidget(Button.builder(
                                Component.literal("Join World").withColor(0xFF7AE89B),
                                b -> JoinFlow.begin(f))
                        .bounds(curX, btnY, 76, 18).build());
                curX += 76 + gap;
            } else if (f.hasJoinableServer()) {
                addRenderableWidget(Button.builder(
                                Component.literal("Join Server").withColor(0xFF7CC8FF),
                                b -> JoinFlow.joinExternalServer(f.serverName(), f.serverIp()))
                        .bounds(curX, btnY, 78, 18).build());
                curX += 78 + gap;
            } else if (f.isInGame()) {
                // Only offer Request Invite when the friend is actually in a world/server!
                addRenderableWidget(Button.builder(
                                Component.literal("Request Invite"),
                                b -> {
                                    if (BurrowClient.connection() != null && f.uuid() != null) {
                                        BurrowClient.connection().requestInvite(f.uuid());
                                    }
                                    b.active = false;
                                    b.setMessage(Component.literal("Requested"));
                                })
                        .bounds(curX, btnY, 92, 18).build());
                curX += 92 + gap;
            }

            // Invite button is available whenever YOU are in a singleplayer world & friend is online
            // (clicking automatically starts hosting if not already hosting!).
            if (inSingleplayer && f.isOnline()) {
                addRenderableWidget(Button.builder(
                                Component.literal("Invite"),
                                b -> {
                                    if (HostSession.inviteFriend(f.uuid())) {
                                        b.active = false;
                                        b.setMessage(Component.literal("Invited"));
                                    }
                                })
                        .bounds(curX, btnY, 54, 18).build());
            }
        }

        // Right-aligned Remove Friend button
        int remW = 54;
        addRenderableWidget(Button.builder(
                        Component.literal("Remove").withColor(0xFFFF8A8A),
                        b -> {
                            if (this.minecraft != null) {
                                this.minecraft.gui.setScreen(new RemoveFriendScreen(this, f));
                            }
                        })
                .bounds(x + w - remW - 6, btnY, remW, 18).build());
    }

    private record WorldEntry(UUID host, String hostName, String subtitle, String badge, int badgeColor, boolean locked) {}

    private List<WorldEntry> collectDiscoverableWorlds() {
        List<WorldEntry> items = new ArrayList<>();
        // Include world invites from non-friends (friend invites already surface right in Friends!)
        for (FriendsState.WorldInvite inv : BurrowClient.friends().worldInvites()) {
            if (BurrowClient.friends().isFriend(inv.host())) {
                continue;
            }
            String sub = inv.worldName() + " · Invited by " + inv.invitedByName();
            items.add(new WorldEntry(inv.host(), inv.hostName(), sub, "Invited", BurrowUi.DOT_NOTIFY, false));
        }
        for (FriendsState.DiscoverableWorld w : BurrowClient.friends().discoverableWorlds()) {
            boolean alreadyListed = items.stream().anyMatch(i -> w.host().equals(i.host()));
            if (alreadyListed) continue;
            String badge;
            int color;
            if ("friend_of_friend".equals(w.source()) && !w.viaName().isEmpty()) {
                badge = "via " + w.viaName();
                color = BurrowUi.ROLE_VISITOR;
            } else if ("open".equals(w.access())) {
                badge = "Open";
                color = BurrowUi.ROLE_MEMBER;
            } else {
                badge = "World";
                color = BurrowUi.ROLE_HOST;
            }
            String count = w.maxPlayers() > 0 ? " (" + w.playerCount() + "/" + w.maxPlayers() + ")" : "";
            String sub = w.worldName() + count + (w.locked() ? " · Locked" : "");
            items.add(new WorldEntry(w.host(), w.hostName(), sub, badge, color, w.locked()));
        }
        return items;
    }

    private void initWorldsTab(List<WorldEntry> items) {
        if (items.isEmpty()) {
            this.emptyMessage = "No open or friends-of-friends worlds right now.";
            return;
        }

        int innerX = this.contentX + 8;
        int innerW = this.contentW - 16;
        int perPage = 3;
        int pages = Math.max(1, (items.size() + perPage - 1) / perPage);
        if (this.page >= pages) this.page = 0;
        int from = this.page * perPage;
        int to = Math.min(items.size(), from + perPage);

        int y = this.contentY + 8;
        for (WorldEntry item : items.subList(from, to)) {
            this.worldCards.add(new CardDisplay(
                    item.host(), item.hostName(), item.subtitle(),
                    item.badge(), item.badgeColor(),
                    innerX, y, innerW, 30));
            Button joinBtn = Button.builder(
                            Component.literal(item.locked() ? "Locked" : "Join")
                                    .withColor(item.locked() ? BurrowUi.TEXT_MUTED : 0xFF7AE89B),
                            b -> JoinFlow.beginByHostUuid(item.host()))
                    .bounds(innerX + innerW - 56, y + 6, 50, 18).build();
            joinBtn.active = !item.locked();
            addRenderableWidget(joinBtn);
            y += 34;
        }

        if (pages > 1) {
            addPager(innerX, this.contentY + CONTENT_H - 20, innerW, pages);
        }
    }

    private void addPager(int x, int y, int w, int pages) {
        Button prev = Button.builder(Component.literal("< Prev"), b -> {
            this.page--;
            rebuildWidgets();
        }).bounds(x, y, 52, 16).build();
        prev.active = this.page > 0;
        addRenderableWidget(prev);

        Button next = Button.builder(Component.literal("Next >"), b -> {
            this.page++;
            rebuildWidgets();
        }).bounds(x + w - 52, y, 52, 16).build();
        next.active = this.page < pages - 1;
        addRenderableWidget(next);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);

        BurrowUi.drawModalPanel(g, this.panelX, this.panelY, PANEL_W, PANEL_H);

        g.text(this.font, "Friends", this.contentX, this.panelY + BurrowUi.HEADER_TITLE_Y, BurrowUi.TEXT_PRIMARY, true);
        String flash = BurrowClient.friends().errorFlash();
        this.showingErrorFlash = (flash != null);
        if (flash != null) {
            g.text(this.font, ellipsize(this.font, "! " + flash, 290),
                    this.contentX, this.panelY + BurrowUi.HEADER_SUB_Y, 0xFFFF9977, false);
        } else {
            g.text(this.font, statusText(), this.contentX, this.panelY + BurrowUi.HEADER_SUB_Y, BurrowUi.TEXT_SECONDARY, false);
        }

        BurrowUi.drawInsetBox(g, this.contentX, this.contentY, this.contentW, CONTENT_H);

        for (CardDisplay c : this.worldCards) {
            g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + c.h(), BurrowUi.CARD_BG);
            g.outline(c.x(), c.y(), c.w(), c.h(), BurrowUi.CARD_BORDER);
            PlayerHeads.draw(g, c.uuid(), c.name(), c.x() + 5, c.y() + 5, 20);
            int textX = c.x() + 31;
            g.text(this.font, c.name(), textX, c.y() + 5, BurrowUi.TEXT_PRIMARY, true);
            if (c.badge() != null && !c.badge().isEmpty()) {
                int nameW = this.font.width(c.name());
                BurrowUi.drawTagPill(g, this.font, textX + nameW + 5, c.y() + 4, c.badge(), c.badgeColor());
            }
            int maxSubW = c.w() - 96;
            g.text(this.font, ellipsize(this.font, c.subtitle(), maxSubW),
                    textX, c.y() + 17, BurrowUi.TEXT_SECONDARY, false);
        }

        if (this.emptyMessage != null) {
            g.centeredText(this.font, this.emptyMessage,
                    this.contentX + this.contentW / 2,
                    this.contentY + CONTENT_H / 2 - 4,
                    BurrowUi.TEXT_MUTED);
        }
    }

    private List<FriendsState.Friend> sortedFriends() {
        List<FriendsState.Friend> list = new ArrayList<>(BurrowClient.friends().friends());
        list.sort(Comparator
                .comparingInt((FriendsState.Friend f) -> {
                    boolean hasNotif = BurrowClient.friends().worldInviteFrom(f.uuid()) != null
                            || BurrowClient.friends().hasInviteRequestFrom(f.uuid());
                    if (hasNotif) return 0;
                    if (f.hosting()) return 1;
                    if (f.hasJoinableServer()) return 2;
                    if (f.isInGame()) return 3;
                    if (f.isOnline()) return 4;
                    return 5;
                })
                .thenComparing(f -> f.name() == null ? "" : f.name().toLowerCase()));
        return list;
    }

    private void switchTab(Tab next) {
        this.tab = next;
        this.page = 0;
        this.expandedFriend = null;
        rebuildWidgets();
    }

    @Override
    public void added() {
        BurrowClient.friends().addListener(this.listener);
    }

    @Override
    public void removed() {
        BurrowClient.friends().removeListener(this.listener);
    }

    @Override
    public void tick() {
        super.tick();
        BurrowClient.friends().pruneExpired();
        if (this.showingErrorFlash && BurrowClient.friends().errorFlash() == null) {
            rebuildWidgets();
        }
    }

    @Override
    public void onClose() {
        if (this.parent != null && this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }

    private void onFriendsChanged() {
        if (this.minecraft == null || this.minecraft.gui == null) {
            return;
        }
        if (this.minecraft.gui.screen() instanceof BurrowFriendsScreen fs) {
            fs.rebuildWidgets();
        }
    }

    private String statusText() {
        BurrowConnection connection = BurrowClient.connection();
        BurrowConnection.Status status =
                connection == null ? BurrowConnection.Status.OFFLINE : connection.status();
        String self = BurrowClient.friends().selfName();
        boolean inc = BurrowClient.config() != null && BurrowClient.config().incognito;
        return switch (status) {
            case CONNECTED -> "Connected as " + (self == null ? "?" : self) + (inc ? " · Incognito" : "");
            case CONNECTING -> "Connecting to Burrow...";
            case ERROR -> "Offline — retrying...";
            case OFFLINE -> "Offline — server unreachable";
        };
    }

    static String ellipsize(Font font, String s, int maxWidth) {
        if (s == null || font.width(s) <= maxWidth) return s == null ? "" : s;
        String dots = "...";
        int target = Math.max(0, maxWidth - font.width(dots));
        return font.plainSubstrByWidth(s, target) + dots;
    }

    /** Sleek dark friend card with skin head, corner status dot (blue for invites/requests), and inline drawer. */
    private static final class FriendCardWidget extends AbstractWidget {
        private final FriendsState.Friend friend;
        private final @Nullable FriendsState.WorldInvite worldInvite;
        private final boolean requestedInvite;
        private final boolean expanded;
        private final Runnable onToggle;

        FriendCardWidget(
                int x, int y, int w, int h,
                FriendsState.Friend friend,
                @Nullable FriendsState.WorldInvite worldInvite,
                boolean requestedInvite,
                boolean expanded,
                Runnable onToggle
        ) {
            super(x, y, w, h, Component.literal(friend.name()));
            this.friend = friend;
            this.worldInvite = worldInvite;
            this.requestedInvite = requestedInvite;
            this.expanded = expanded;
            this.onToggle = onToggle;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();

            int bg = this.expanded
                    ? BurrowUi.CARD_SELECTED
                    : (this.isHovered ? BurrowUi.CARD_HOVER : BurrowUi.CARD_BG);
            g.fill(x, y, x + w, y + h, bg);
            g.outline(x, y, w, h, this.expanded ? BurrowUi.CARD_BORDER_ACTIVE : BurrowUi.CARD_BORDER);

            if (this.expanded) {
                g.fill(x, y + h, x + w, y + h + 24, BurrowUi.DRAWER_BG);
                g.outline(x, y + h - 1, w, 25, BurrowUi.CARD_BORDER_ACTIVE);
            }

            // Player skin head + corner status dot (blue if invite sent or invite requested!)
            PlayerHeads.draw(g, this.friend.uuid(), this.friend.name(), x + 5, y + 5, 20);
            int dotColor;
            if (this.worldInvite != null || this.requestedInvite) {
                dotColor = BurrowUi.DOT_NOTIFY;
            } else if (this.friend.hosting()) {
                dotColor = this.friend.locked() ? BurrowUi.DOT_LOCKED : BurrowUi.DOT_LIVE;
            } else if (this.friend.isOnline()) {
                dotColor = BurrowUi.DOT_LIVE;
            } else {
                dotColor = BurrowUi.DOT_OFFLINE;
            }
            g.fill(x + 20, y + 20, x + 26, y + 26, 0xFF0E0E12);
            g.fill(x + 21, y + 21, x + 25, y + 25, dotColor);

            Font font = Minecraft.getInstance().font;
            int textX = x + 32;
            int nameColor = this.friend.isOnline() ? BurrowUi.TEXT_PRIMARY : BurrowUi.TEXT_SECONDARY;
            g.text(font, this.friend.name(), textX, y + 5, nameColor, true);

            // Compact crisp 0.75x tag pill next to name
            int nameW = font.width(this.friend.name());
            if (this.worldInvite != null) {
                BurrowUi.drawTagPill(g, font, textX + nameW + 5, y + 4, "Invited You", BurrowUi.DOT_NOTIFY);
            } else if (this.requestedInvite) {
                BurrowUi.drawTagPill(g, font, textX + nameW + 5, y + 4, "Wants to Join", BurrowUi.DOT_NOTIFY);
            } else if (this.friend.hosting()) {
                String badge = this.friend.locked() ? "Locked" : "Hosting";
                int color = this.friend.locked() ? BurrowUi.DOT_LOCKED : BurrowUi.ROLE_MEMBER;
                BurrowUi.drawTagPill(g, font, textX + nameW + 5, y + 4, badge, color);
            } else if (this.friend.hasJoinableServer()) {
                BurrowUi.drawTagPill(g, font, textX + nameW + 5, y + 4, "Server", BurrowUi.ROLE_VISITOR);
            }

            String subtitle;
            if (this.worldInvite != null) {
                subtitle = "Invited you to " + this.worldInvite.worldName();
            } else if (this.requestedInvite) {
                subtitle = "Requested an invite to your world";
            } else {
                subtitle = this.friend.activitySubtitle();
            }
            int subColor = (this.worldInvite != null || this.requestedInvite)
                    ? BurrowUi.DOT_NOTIFY
                    : (this.friend.isOnline() ? BurrowUi.TEXT_SECONDARY : BurrowUi.TEXT_MUTED);
            g.text(font, ellipsize(font, subtitle, w - 52), textX, y + 17, subColor, false);

            String chevron = this.expanded ? "v" : ">";
            int chevColor = this.isHovered || this.expanded ? BurrowUi.TEXT_PRIMARY : BurrowUi.TEXT_MUTED;
            g.text(font, chevron, x + w - 12, y + 11, chevColor, false);
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

    /** Same-size dedicated modal for adding friends and managing friend requests. */
    static class FriendRequestsScreen extends Screen {
        private static final int MODAL_W = BurrowUi.PANEL_W;
        private static final int MODAL_H = BurrowUi.PANEL_H;
        private static final int PAD = BurrowUi.PAD;

        private final Screen parent;
        private final FriendsState.Listener listener = () -> {
            if (this.minecraft != null && this.minecraft.gui.screen() == this) {
                syncTypedName();
                rebuildWidgets();
            }
        };
        private int modalX;
        private int modalY;
        private int innerX;
        private int innerW;
        private int page = 0;
        private String typedName = "";
        private @Nullable EditBox nameBox;
        private final List<CardDisplay> rows = new ArrayList<>();
        private @Nullable String emptyMsg;
        private boolean showingFlash = false;

        FriendRequestsScreen(Screen parent) {
            super(Component.literal("Add Friend & Requests"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            this.rows.clear();
            this.emptyMsg = null;
            this.modalX = (this.width - MODAL_W) / 2;
            this.modalY = Math.max(2, (this.height - MODAL_H) / 2);
            this.innerX = this.modalX + PAD;
            this.innerW = MODAL_W - PAD * 2;

            // Add friend row at top of inset box
            int boxX = this.innerX + 8;
            int boxW = this.innerW - 16;
            int sendW = 54;
            this.nameBox = new EditBox(this.font, boxX, this.modalY + 49, boxW - sendW - 4, 18,
                    Component.literal("Add friend"));
            this.nameBox.setMaxLength(32);
            this.nameBox.setHint(Component.literal("Enter username..."));
            this.nameBox.setValue(this.typedName);
            addRenderableWidget(this.nameBox);
            addRenderableWidget(Button.builder(Component.literal("Send"), b -> sendRequest())
                    .bounds(boxX + boxW - sendW, this.modalY + 48, sendW, 20).build());
            setInitialFocus(this.nameBox);

            List<FriendsState.Request> requests = BurrowClient.friends().requests();
            if (requests.isEmpty()) {
                this.emptyMsg = "No pending friend requests.";
            } else {
                int perPage = 3;
                int pages = Math.max(1, (requests.size() + perPage - 1) / perPage);
                if (this.page >= pages) this.page = 0;
                int from = this.page * perPage;
                int to = Math.min(requests.size(), from + perPage);

                int y = this.modalY + 76;
                for (FriendsState.Request r : requests.subList(from, to)) {
                    if (r.outgoing()) {
                        this.rows.add(new CardDisplay(r.uuid(), r.name(), "Outgoing request",
                                null, 0, boxX, y, boxW, 28));
                        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
                            if (BurrowClient.connection() != null) {
                                BurrowClient.connection().declineRequest(r.uuid());
                            }
                        }).bounds(boxX + boxW - 58, y + 5, 52, 18).build());
                    } else {
                        this.rows.add(new CardDisplay(r.uuid(), r.name(), "Incoming request",
                                null, 0, boxX, y, boxW, 28));
                        addRenderableWidget(Button.builder(Component.literal("Accept").withColor(0xFF7AE89B), b -> {
                            if (BurrowClient.connection() != null) {
                                BurrowClient.connection().acceptRequest(r.uuid());
                            }
                        }).bounds(boxX + boxW - 110, y + 5, 50, 18).build());
                        addRenderableWidget(Button.builder(Component.literal("Decline").withColor(0xFFFF8A8A), b -> {
                            if (BurrowClient.connection() != null) {
                                BurrowClient.connection().declineRequest(r.uuid());
                            }
                        }).bounds(boxX + boxW - 56, y + 5, 50, 18).build());
                    }
                    y += 32;
                }

                if (pages > 1) {
                    Button prev = Button.builder(Component.literal("< Prev"), b -> {
                        this.page--;
                        rebuildWidgets();
                    }).bounds(boxX, this.modalY + 175, 52, 16).build();
                    prev.active = this.page > 0;
                    addRenderableWidget(prev);

                    Button next = Button.builder(Component.literal("Next >"), b -> {
                        this.page++;
                        rebuildWidgets();
                    }).bounds(boxX + boxW - 52, this.modalY + 175, 52, 16).build();
                    next.active = this.page < pages - 1;
                    addRenderableWidget(next);
                }
            }

            addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                    .bounds(this.innerX + this.innerW - 84, this.modalY + BurrowUi.FOOTER_Y, 84, 20).build());
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            super.extractBackground(g, mouseX, mouseY, partialTick);
            BurrowUi.drawModalPanel(g, this.modalX, this.modalY, MODAL_W, MODAL_H);
            g.text(this.font, "Add Friend & Requests", this.innerX, this.modalY + BurrowUi.HEADER_TITLE_Y, BurrowUi.TEXT_PRIMARY, true);

            String flash = BurrowClient.friends().errorFlash();
            this.showingFlash = (flash != null);
            if (flash != null) {
                g.text(this.font, ellipsize(this.font, "! " + flash, this.innerW),
                        this.innerX, this.modalY + BurrowUi.HEADER_SUB_Y, 0xFFFF9977, false);
            } else {
                g.text(this.font, "Add someone by username or manage pending requests",
                        this.innerX, this.modalY + BurrowUi.HEADER_SUB_Y, BurrowUi.TEXT_SECONDARY, false);
            }

            BurrowUi.drawInsetBox(g, this.innerX, this.modalY + 40, this.innerW, 155);

            for (CardDisplay c : this.rows) {
                g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + c.h(), BurrowUi.CARD_BG);
                g.outline(c.x(), c.y(), c.w(), c.h(), BurrowUi.CARD_BORDER);
                PlayerHeads.draw(g, c.uuid(), c.name(), c.x() + 5, c.y() + 4, 20);
                g.text(this.font, c.name(), c.x() + 31, c.y() + 5, BurrowUi.TEXT_PRIMARY, true);
                g.text(this.font, c.subtitle(), c.x() + 31, c.y() + 16, BurrowUi.TEXT_SECONDARY, false);
            }

            if (this.emptyMsg != null) {
                g.centeredText(this.font, this.emptyMsg,
                        this.modalX + MODAL_W / 2, this.modalY + 124, BurrowUi.TEXT_MUTED);
            }
        }

        @Override
        public void added() {
            BurrowClient.friends().addListener(this.listener);
        }

        @Override
        public void removed() {
            BurrowClient.friends().removeListener(this.listener);
        }

        @Override
        public void tick() {
            super.tick();
            if (this.showingFlash && BurrowClient.friends().errorFlash() == null) {
                syncTypedName();
                rebuildWidgets();
            }
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (this.nameBox != null && this.nameBox.isFocused()
                    && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
                sendRequest();
                return true;
            }
            return super.keyPressed(event);
        }

        void syncTypedName() {
            if (this.nameBox != null) {
                this.typedName = this.nameBox.getValue();
            }
        }

        private void sendRequest() {
            if (this.nameBox == null) return;
            String name = this.nameBox.getValue().trim();
            if (name.isEmpty()) return;
            this.typedName = "";
            this.nameBox.setValue("");
            if (BurrowClient.connection() != null) {
                BurrowClient.connection().addFriend(name);
            }
        }

        @Override
        public void onClose() {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(this.parent);
            }
        }
    }

    /** Vanilla-styled confirmation modal before removing a friend. */
    private static class RemoveFriendScreen extends Screen {
        private static final int MODAL_W = 240;
        private static final int MODAL_H = 104;
        private final Screen parent;
        private final FriendsState.Friend friend;
        private int modalX;
        private int modalY;

        RemoveFriendScreen(Screen parent, FriendsState.Friend friend) {
            super(Component.literal("Remove Friend"));
            this.parent = parent;
            this.friend = friend;
        }

        @Override
        protected void init() {
            this.modalX = (this.width - MODAL_W) / 2;
            this.modalY = (this.height - MODAL_H) / 2;

            int btnY = this.modalY + MODAL_H - 30;
            addRenderableWidget(Button.builder(Component.literal("Remove").withColor(0xFFFF8A8A), b -> {
                if (BurrowClient.connection() != null && this.friend.uuid() != null) {
                    BurrowClient.connection().removeFriend(this.friend.uuid());
                }
                onClose();
            }).bounds(this.modalX + 10, btnY, 106, 20).build());

            addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                    .bounds(this.modalX + MODAL_W - 116, btnY, 106, 20).build());
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            super.extractBackground(g, mouseX, mouseY, partialTick);
            BurrowUi.drawModalPanel(g, this.modalX, this.modalY, MODAL_W, MODAL_H);
            g.text(this.font, "Remove Friend", this.modalX + 10, this.modalY + 10, BurrowUi.TEXT_PRIMARY, true);
            PlayerHeads.draw(g, this.friend.uuid(), this.friend.name(), this.modalX + 12, this.modalY + 34, 20);
            g.text(this.font, "Remove " + this.friend.name() + "?",
                    this.modalX + 40, this.modalY + 40, BurrowUi.TEXT_SECONDARY, false);
        }

        @Override
        public void onClose() {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(this.parent);
            }
        }
    }
}
