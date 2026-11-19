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
import net.minecraft.client.gui.components.Tooltip;
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
 * Burrow's vanilla-styled Friends modal, sharing the same 9-slice panel,
 * tabs, player skin heads, and inline expandable cards as {@link HostWorldScreen}.
 */
public class BurrowFriendsScreen extends Screen {
    private static final int PANEL_W = 356;
    private static final int PANEL_H = 234;
    private static final int PAD_X = 12;
    private static final int TABS_Y_OFFSET = 40;
    private static final int TAB_H = 20;
    private static final int CONTENT_Y_OFFSET = 59;
    private static final int CONTENT_H = 136;
    private static final int FOOTER_Y_OFFSET = 203;

    private enum Tab { FRIENDS, WORLDS, REQUESTS }

    private final @Nullable Screen parent;
    private final FriendsState.Listener listener = this::onFriendsChanged;
    private Tab tab = Tab.FRIENDS;
    private @Nullable UUID expandedFriend = null;
    private int page = 0;
    private String typedName = "";
    private @Nullable EditBox nameBox;
    private boolean showingErrorFlash = false;

    // Layout geometry & static card rows rendered in extractBackground
    private int panelX;
    private int panelY;
    private int contentX;
    private int contentY;
    private int contentW;
    private final List<CardDisplay> staticCards = new ArrayList<>();
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
        this.staticCards.clear();
        this.emptyMessage = null;
        this.nameBox = null;

        this.panelX = (this.width - PANEL_W) / 2;
        this.panelY = Math.max(3, (this.height - PANEL_H) / 2);
        this.contentX = this.panelX + PAD_X;
        this.contentY = this.panelY + CONTENT_Y_OFFSET;
        this.contentW = PANEL_W - PAD_X * 2;

        Minecraft mc = this.minecraft;
        BurrowConfig cfg = BurrowClient.config();

        // ----- Header Incognito Toggle (top-right) -----
        boolean incognito = cfg != null && cfg.incognito;
        Component incLabel = incognito
                ? Component.literal("Incognito: On").withColor(0xFFD8B0FF)
                : Component.literal("Incognito: Off");
        Button incBtn = Button.builder(incLabel, b -> {
            if (cfg != null) {
                cfg.incognito = !cfg.incognito;
                cfg.save();
                ActivityTracker.pushCurrent();
                rebuildWidgets();
            }
        }).bounds(this.contentX + this.contentW - 96, this.panelY + 12, 96, 18).build();
        incBtn.setTooltip(Tooltip.create(Component.literal(
                "Hides your server/world details from friends and shows only \"Playing Multiplayer\".")));
        addRenderableWidget(incBtn);

        // ----- Tab Counts -----
        List<FriendsState.Friend> friends = sortedFriends();
        List<FriendsState.WorldInvite> invites = BurrowClient.friends().worldInvites();
        List<FriendsState.DiscoverableWorld> discoverable = BurrowClient.friends().discoverableWorlds();
        List<FriendsState.Request> requests = BurrowClient.friends().requests();
        List<FriendsState.InviteRequest> inviteReqs = BurrowClient.friends().inviteRequests();

        int worldsCount = invites.size() + discoverable.size();
        int requestsCount = requests.size() + inviteReqs.size();

        // ----- Vanilla Tabs (flush with content box) -----
        int tabX = this.contentX;
        int tabY = this.panelY + TABS_Y_OFFSET;
        int tabGap = 2;
        int tabW = (this.contentW - tabGap * 2) / 3;
        int lastTabW = this.contentW - (tabW + tabGap) * 2;

        addRenderableWidget(new BurrowUi.TabButton(
                tabX, tabY, tabW, TAB_H,
                Component.literal("Friends (" + friends.size() + ")"),
                this.tab == Tab.FRIENDS,
                () -> switchTab(Tab.FRIENDS)));
        addRenderableWidget(new BurrowUi.TabButton(
                tabX + tabW + tabGap, tabY, tabW, TAB_H,
                Component.literal(worldsCount > 0 ? "Worlds (" + worldsCount + ")" : "Worlds"),
                this.tab == Tab.WORLDS,
                () -> switchTab(Tab.WORLDS)));
        addRenderableWidget(new BurrowUi.TabButton(
                tabX + (tabW + tabGap) * 2, tabY, lastTabW, TAB_H,
                Component.literal(requestsCount > 0 ? "Requests (" + requestsCount + ")" : "Requests"),
                this.tab == Tab.REQUESTS,
                () -> switchTab(Tab.REQUESTS)));

        // ----- Active Tab Body -----
        switch (this.tab) {
            case FRIENDS -> initFriendsTab(friends);
            case WORLDS -> initWorldsTab(invites, discoverable);
            case REQUESTS -> initRequestsTab(requests, inviteReqs);
        }

        // ----- Footer Bar -----
        int footerY = this.panelY + FOOTER_Y_OFFSET;
        if (mc.getSingleplayerServer() != null) {
            boolean hosting = HostSession.isHosting();
            addRenderableWidget(Button.builder(
                            Component.literal(hosting ? "Multiplayer Settings" : "Host World"),
                            b -> mc.gui.setScreen(new HostWorldScreen(this)))
                    .bounds(this.contentX, footerY, 130, 20).build());
        } else {
            HostSession.ensureStopped();
            addRenderableWidget(Button.builder(
                            Component.literal("+ Add Friend"),
                            b -> {
                                this.tab = Tab.REQUESTS;
                                this.page = 0;
                                this.expandedFriend = null;
                                rebuildWidgets();
                            })
                    .bounds(this.contentX, footerY, 102, 20).build());
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
            this.emptyMessage = "No friends yet — open Requests to add someone.";
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
            addRenderableWidget(new FriendCardWidget(
                    innerX, y, innerW, 30, f, expanded,
                    () -> {
                        this.expandedFriend = expanded ? null : f.uuid();
                        rebuildWidgets();
                    }));
            y += 30;
            if (expanded) {
                addFriendDrawer(f, innerX, y, innerW);
                y += 26;
            }
            y += 4;
        }

        if (pages > 1) {
            addPager(innerX, this.contentY + CONTENT_H - 20, innerW, pages);
        }
    }

    private void addFriendDrawer(FriendsState.Friend f, int x, int y, int w) {
        int btnY = y + 3;
        int curX = x + 6;
        int gap = 4;

        // Primary join / request action
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
        } else if (f.isOnline()) {
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

        // If we are currently hosting a world and this friend is online, let us invite them!
        if (HostSession.isHosting() && f.isOnline()) {
            addRenderableWidget(Button.builder(
                            Component.literal("Invite"),
                            b -> {
                                if (BurrowClient.connection() != null && f.uuid() != null) {
                                    BurrowClient.connection().sessionInvite(f.uuid(), null);
                                }
                                b.active = false;
                                b.setMessage(Component.literal("Invited"));
                            })
                    .bounds(curX, btnY, 54, 18).build());
        }

        // Right-aligned Remove Friend button
        int remW = 58;
        addRenderableWidget(Button.builder(
                        Component.literal("Remove").withColor(0xFFFF8A8A),
                        b -> {
                            if (this.minecraft != null) {
                                this.minecraft.gui.setScreen(new RemoveFriendScreen(this, f));
                            }
                        })
                .bounds(x + w - remW - 6, btnY, remW, 18).build());
    }

    private void initWorldsTab(
            List<FriendsState.WorldInvite> invites,
            List<FriendsState.DiscoverableWorld> discoverable
    ) {
        record WorldEntry(UUID host, String hostName, String subtitle, String badge, int badgeColor, boolean locked) {}
        List<WorldEntry> items = new ArrayList<>();
        for (FriendsState.WorldInvite inv : invites) {
            String sub = inv.worldName() + " · Invited by " + inv.invitedByName();
            items.add(new WorldEntry(inv.host(), inv.hostName(), sub, "Invited", BurrowUi.ROLE_HOST, false));
        }
        for (FriendsState.DiscoverableWorld w : discoverable) {
            boolean alreadyListed = invites.stream().anyMatch(i -> w.host().equals(i.host()));
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

        if (items.isEmpty()) {
            this.emptyMessage = "No world invites or open worlds right now.";
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
            this.staticCards.add(new CardDisplay(
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

    private void initRequestsTab(
            List<FriendsState.Request> requests,
            List<FriendsState.InviteRequest> inviteReqs
    ) {
        int innerX = this.contentX + 8;
        int innerW = this.contentW - 16;

        // Top row: Add friend input + Send button
        int sendW = 54;
        this.nameBox = new EditBox(this.font, innerX, this.contentY + 8, innerW - sendW - 4, 18,
                Component.literal("Add friend"));
        this.nameBox.setMaxLength(32);
        this.nameBox.setHint(Component.literal("Add friend by username..."));
        this.nameBox.setValue(this.typedName);
        addRenderableWidget(this.nameBox);
        addRenderableWidget(Button.builder(Component.literal("Send"), b -> sendRequest())
                .bounds(innerX + innerW - sendW, this.contentY + 7, sendW, 20).build());
        setInitialFocus(this.nameBox);

        // Combine incoming invite requests + incoming/outgoing friend requests
        record ReqItem(boolean isInviteReq, UUID uuid, String name, boolean outgoing) {}
        List<ReqItem> all = new ArrayList<>();
        for (FriendsState.InviteRequest ir : inviteReqs) {
            all.add(new ReqItem(true, ir.from(), ir.fromName(), false));
        }
        for (FriendsState.Request r : requests) {
            all.add(new ReqItem(false, r.uuid(), r.name(), r.outgoing()));
        }

        if (all.isEmpty()) {
            this.emptyMessage = "No pending requests.";
            return;
        }

        int perPage = 2;
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        if (this.page >= pages) this.page = 0;
        int from = this.page * perPage;
        int to = Math.min(all.size(), from + perPage);

        int y = this.contentY + 33;
        for (ReqItem item : all.subList(from, to)) {
            if (item.isInviteReq()) {
                this.staticCards.add(new CardDisplay(
                        item.uuid(), item.name(), "Requested an invite to join you",
                        "Invite", BurrowUi.ROLE_HOST,
                        innerX, y, innerW, 30));
                if (HostSession.isHosting()) {
                    addRenderableWidget(Button.builder(Component.literal("Invite").withColor(0xFF7AE89B), b -> {
                        if (BurrowClient.connection() != null) {
                            BurrowClient.connection().sessionInvite(item.uuid(), null);
                        }
                        BurrowClient.friends().dismissInviteRequest(item.uuid());
                    }).bounds(innerX + innerW - 108, y + 6, 48, 18).build());
                }
                addRenderableWidget(Button.builder(Component.literal("Dismiss"), b ->
                        BurrowClient.friends().dismissInviteRequest(item.uuid()))
                        .bounds(innerX + innerW - 56, y + 6, 50, 18).build());
            } else if (item.outgoing()) {
                this.staticCards.add(new CardDisplay(
                        item.uuid(), item.name(), "Outgoing friend request",
                        null, 0, innerX, y, innerW, 30));
                addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
                    if (BurrowClient.connection() != null) {
                        BurrowClient.connection().declineRequest(item.uuid());
                    }
                }).bounds(innerX + innerW - 58, y + 6, 52, 18).build());
            } else {
                this.staticCards.add(new CardDisplay(
                        item.uuid(), item.name(), "Incoming friend request",
                        null, 0, innerX, y, innerW, 30));
                addRenderableWidget(Button.builder(Component.literal("Accept").withColor(0xFF7AE89B), b -> {
                    if (BurrowClient.connection() != null) {
                        BurrowClient.connection().acceptRequest(item.uuid());
                    }
                }).bounds(innerX + innerW - 110, y + 6, 50, 18).build());
                addRenderableWidget(Button.builder(Component.literal("Decline").withColor(0xFFFF8A8A), b -> {
                    if (BurrowClient.connection() != null) {
                        BurrowClient.connection().declineRequest(item.uuid());
                    }
                }).bounds(innerX + innerW - 56, y + 6, 50, 18).build());
            }
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

        // Header title & live connection / error status
        g.text(this.font, "Friends", this.contentX, this.panelY + 11, BurrowUi.TEXT_PRIMARY, true);
        String flash = BurrowClient.friends().errorFlash();
        this.showingErrorFlash = (flash != null);
        if (flash != null) {
            g.text(this.font, ellipsize(this.font, "! " + flash, 224),
                    this.contentX, this.panelY + 23, 0xFFFF9977, false);
        } else {
            g.text(this.font, statusText(), this.contentX, this.panelY + 23, BurrowUi.TEXT_SECONDARY, false);
        }

        // Recessed flat dark inset container
        BurrowUi.drawInsetBox(g, this.contentX, this.contentY, this.contentW, CONTENT_H);

        // Static cards (Worlds & Requests tabs)
        for (CardDisplay c : this.staticCards) {
            g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + c.h(), BurrowUi.CARD_BG);
            g.outline(c.x(), c.y(), c.w(), c.h(), 0x22FFFFFF);
            PlayerHeads.draw(g, c.uuid(), c.name(), c.x() + 5, c.y() + 5, 20);
            int textX = c.x() + 31;
            g.text(this.font, c.name(), textX, c.y() + 5, BurrowUi.TEXT_PRIMARY, true);
            if (c.badge() != null && !c.badge().isEmpty()) {
                int nameW = this.font.width(c.name());
                BurrowUi.drawTagPill(g, this.font, textX + nameW + 6, c.y() + 3, c.badge(), c.badgeColor());
            }
            int maxSubW = c.w() - 150;
            g.text(this.font, ellipsize(this.font, c.subtitle(), maxSubW),
                    textX, c.y() + 17, BurrowUi.TEXT_SECONDARY, false);
        }

        if (this.emptyMessage != null) {
            int msgY = (this.tab == Tab.REQUESTS)
                    ? this.contentY + 74
                    : this.contentY + CONTENT_H / 2 - 4;
            g.centeredText(this.font, this.emptyMessage,
                    this.contentX + this.contentW / 2, msgY, BurrowUi.TEXT_MUTED);
        }
    }

    private List<FriendsState.Friend> sortedFriends() {
        List<FriendsState.Friend> list = new ArrayList<>(BurrowClient.friends().friends());
        list.sort(Comparator
                .comparingInt((FriendsState.Friend f) -> {
                    if (f.hosting()) return 0;
                    if (f.hasJoinableServer()) return 1;
                    if (f.isOnline()) return 2;
                    return 3;
                })
                .thenComparing(f -> f.name() == null ? "" : f.name().toLowerCase()));
        return list;
    }

    private void switchTab(Tab next) {
        syncTypedName();
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
        if (this.showingErrorFlash && BurrowClient.friends().errorFlash() == null) {
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

    @Override
    public void onClose() {
        if (this.parent != null && this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }

    private void syncTypedName() {
        if (this.nameBox != null) {
            this.typedName = this.nameBox.getValue();
        }
    }

    private void onFriendsChanged() {
        if (this.minecraft == null || this.minecraft.gui == null) {
            return;
        }
        if (this.minecraft.gui.screen() instanceof BurrowFriendsScreen current) {
            current.syncTypedName();
            current.rebuildWidgets();
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

    private String statusText() {
        BurrowConnection connection = BurrowClient.connection();
        BurrowConnection.Status status =
                connection == null ? BurrowConnection.Status.OFFLINE : connection.status();
        String self = BurrowClient.friends().selfName();
        return switch (status) {
            case CONNECTED -> "Connected as " + (self == null ? "?" : self);
            case CONNECTING -> "Connecting to Burrow...";
            case ERROR -> "Offline — retrying...";
            case OFFLINE -> "Offline — server unreachable";
        };
    }

    private static String ellipsize(Font font, String s, int maxWidth) {
        if (s == null || font.width(s) <= maxWidth) return s == null ? "" : s;
        String dots = "...";
        int target = Math.max(0, maxWidth - font.width(dots));
        String trimmed = font.plainSubstrByWidth(s, target);
        return trimmed + dots;
    }

    /** Sleek dark friend card with skin head, status dot, activity subtitle, and expandable drawer. */
    private static final class FriendCardWidget extends AbstractWidget {
        private final FriendsState.Friend friend;
        private final boolean expanded;
        private final Runnable onToggle;

        FriendCardWidget(int x, int y, int w, int h, FriendsState.Friend friend, boolean expanded, Runnable onToggle) {
            super(x, y, w, h, Component.literal(friend.name()));
            this.friend = friend;
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
            g.outline(x, y, w, h, this.expanded ? 0x66FFFFFF : 0x22FFFFFF);

            if (this.expanded) {
                g.fill(x, y + h, x + w, y + h + 25, BurrowUi.DRAWER_BG);
                g.outline(x, y + h - 1, w, 26, 0x33FFFFFF);
            }

            // Player skin head + presence dot on bottom-right corner
            PlayerHeads.draw(g, this.friend.uuid(), this.friend.name(), x + 5, y + 5, 20);
            int dotColor = this.friend.hosting()
                    ? (this.friend.locked() ? BurrowUi.DOT_LOCKED : BurrowUi.DOT_LIVE)
                    : (this.friend.isOnline() ? BurrowUi.DOT_LIVE : BurrowUi.DOT_OFFLINE);
            g.fill(x + 20, y + 20, x + 26, y + 26, 0xFF0A0A0D);
            g.fill(x + 21, y + 21, x + 25, y + 25, dotColor);

            Font font = Minecraft.getInstance().font;
            int textX = x + 32;
            int nameColor = this.friend.isOnline() ? BurrowUi.TEXT_PRIMARY : BurrowUi.TEXT_SECONDARY;
            g.text(font, this.friend.name(), textX, y + 5, nameColor, true);

            // Compact status pill next to name when hosting or on a joinable server
            int nameW = font.width(this.friend.name());
            if (this.friend.hosting()) {
                String badge = this.friend.locked() ? "Locked" : "Hosting";
                int color = this.friend.locked() ? BurrowUi.DOT_LOCKED : BurrowUi.ROLE_MEMBER;
                BurrowUi.drawTagPill(g, font, textX + nameW + 6, y + 3, badge, color);
            } else if (this.friend.hasJoinableServer()) {
                BurrowUi.drawTagPill(g, font, textX + nameW + 6, y + 3, "Server", BurrowUi.ROLE_VISITOR);
            }

            String sub = ellipsize(font, this.friend.activitySubtitle(), w - 52);
            int subColor = this.friend.isOnline() ? BurrowUi.TEXT_SECONDARY : BurrowUi.TEXT_MUTED;
            g.text(font, sub, textX, y + 17, subColor, false);

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
            }).bounds(this.modalX + 12, btnY, 104, 20).build());

            addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                    .bounds(this.modalX + MODAL_W - 116, btnY, 104, 20).build());
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            super.extractBackground(g, mouseX, mouseY, partialTick);
            BurrowUi.drawModalPanel(g, this.modalX, this.modalY, MODAL_W, MODAL_H);
            g.text(this.font, "Remove Friend", this.modalX + 12, this.modalY + 12, BurrowUi.TEXT_PRIMARY, true);
            PlayerHeads.draw(g, this.friend.uuid(), this.friend.name(), this.modalX + 14, this.modalY + 34, 20);
            g.text(this.font, "Remove " + this.friend.name() + "?",
                    this.modalX + 42, this.modalY + 40, BurrowUi.TEXT_SECONDARY, false);
        }

        @Override
        public void onClose() {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(this.parent);
            }
        }
    }
}
