package dev.meeyah.burrow.ui;

import com.mojang.authlib.GameProfile;
import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.compat.ViaFabricPlusCompat;
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
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Burrow's vanilla-styled Friends modal.
 *
 * Three smooth-scrolling tabs (Friends / Players + Recent 50 / discoverable Worlds),
 * compact Do Not Disturb & Discord RPC toggles in the header, clickable favorite
 * stars pinning friends to the top, inline action drawers, and a dedicated
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

    private enum Tab { FRIENDS, PLAYERS, WORLDS }

    private final @Nullable Screen parent;
    private final FriendsState.Listener listener = this::onFriendsChanged;
    private Tab tab = Tab.FRIENDS;
    private @Nullable UUID expandedFriend = null;
    private double scrollFriends = 0.0;
    private double scrollPlayers = 0.0;
    private double scrollWorlds = 0.0;
    private boolean showingErrorFlash = false;
    private @Nullable BurrowConnection.Status lastConnectionStatus = null;
    private boolean checkedPlayers = false;

    private int panelX;
    private int panelY;
    private int contentX;
    private int contentY;
    private int contentW;
    private @Nullable String emptyMessage;

    private record CardDisplay(
            UUID uuid,
            String name,
            String subtitle,
            @Nullable String badge,
            int badgeColor,
            boolean muted,
            int x, int y, int w, int h
    ) {}

    private record SectionLabel(String text, int x, int y) {}

    private record PlayerCandidate(
            UUID uuid,
            String name,
            boolean onlineNow,
            String recentSource,
            long lastSeenMs
    ) {}

    public BurrowFriendsScreen(@Nullable Screen parent) {
        super(Component.literal("Friends"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.emptyMessage = null;

        this.panelX = (this.width - PANEL_W) / 2;
        this.panelY = Math.max(4, (this.height - PANEL_H) / 2);
        this.contentX = this.panelX + PAD_X;
        this.contentY = this.panelY + CONTENT_Y;
        this.contentW = PANEL_W - PAD_X * 2;

        Minecraft mc = this.minecraft;
        BurrowConfig cfg = BurrowClient.config();

        // ----- Compact 20x20 Header Icon Toggles (Discord RPC + Do Not Disturb) -----
        boolean dnd = cfg != null && cfg.doNotDisturb;
        boolean rpcEnabled = cfg == null || cfg.discordRichPresence;
        int dndX = this.contentX + this.contentW - 20;
        int rpcX = dndX - 4 - 20;

        addRenderableWidget(new BurrowUi.DiscordRpcButton(
                rpcX,
                this.panelY + BurrowUi.HEADER_TITLE_Y,
                rpcEnabled,
                dnd,
                () -> {
                    if (cfg != null) {
                        cfg.discordRichPresence = !cfg.discordRichPresence;
                        cfg.save();
                        ActivityTracker.refreshDiscord();
                        rebuildWidgets();
                    }
                }));

        addRenderableWidget(new BurrowUi.DndButton(
                dndX,
                this.panelY + BurrowUi.HEADER_TITLE_Y,
                dnd,
                () -> {
                    if (cfg != null) {
                        cfg.doNotDisturb = !cfg.doNotDisturb;
                        cfg.save();
                        ActivityTracker.pushCurrent();
                        rebuildWidgets();
                    }
                }));

        // Compact 1-click Retry button whenever Burrow is offline/errored so players
        // never need to restart Minecraft to recover from transient Mojang/network errors.
        BurrowConnection conn = BurrowClient.connection();
        BurrowConnection.Status connStatus = conn == null ? BurrowConnection.Status.OFFLINE : conn.status();
        this.lastConnectionStatus = connStatus;
        if (conn != null && (connStatus == BurrowConnection.Status.ERROR || connStatus == BurrowConnection.Status.OFFLINE)) {
            addRenderableWidget(Button.builder(Component.literal("Retry"), b -> {
                conn.reconnectNow();
                rebuildWidgets();
            }).bounds(rpcX - 4 - 46, this.panelY + BurrowUi.HEADER_TITLE_Y, 46, 20).build());
        }

        // ----- Tab Data & Counts -----
        if (mc != null) {
            ActivityTracker.captureRecentPlayers(mc);
        }
        List<FriendsState.Friend> friends = sortedFriends();
        long onlineFriends = friends.stream().filter(FriendsState.Friend::isOnline).count();
        List<PlayerCandidate> players = collectPlayersAndRecent();
        List<WorldEntry> worlds = collectDiscoverableWorlds();

        if (!this.checkedPlayers && !players.isEmpty() && BurrowClient.connection() != null) {
            this.checkedPlayers = true;
            List<UUID> uuids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (PlayerCandidate p : players) {
                if (p.uuid() != null) uuids.add(p.uuid());
                if (p.name() != null && !p.name().isBlank()) names.add(p.name());
            }
            BurrowClient.connection().checkUsers(uuids, names);
        }

        // ----- 3 Vanilla Tabs (Friends / Players / Worlds, flush with inset box) -----
        int tabX = this.contentX;
        int tabY = this.panelY + TABS_Y;
        int tabGap = 2;
        int tabW = (this.contentW - tabGap * 2) / 3;
        int lastTabW = this.contentW - (tabW + tabGap) * 2;

        addRenderableWidget(new BurrowUi.TabButton(
                tabX, tabY, tabW, TAB_H,
                Component.literal("Friends (" + onlineFriends + ")"),
                this.tab == Tab.FRIENDS,
                () -> switchTab(Tab.FRIENDS)));
        addRenderableWidget(new BurrowUi.TabButton(
                tabX + tabW + tabGap, tabY, tabW, TAB_H,
                Component.literal(players.isEmpty() ? "Players" : "Players (" + players.size() + ")"),
                this.tab == Tab.PLAYERS,
                () -> switchTab(Tab.PLAYERS)));
        addRenderableWidget(new BurrowUi.TabButton(
                tabX + (tabW + tabGap) * 2, tabY, lastTabW, TAB_H,
                Component.literal(worlds.isEmpty() ? "Worlds" : "Worlds (" + worlds.size() + ")"),
                this.tab == Tab.WORLDS,
                () -> switchTab(Tab.WORLDS)));

        // ----- Active Tab Body -----
        switch (this.tab) {
            case FRIENDS -> initFriendsTab(friends);
            case PLAYERS -> initPlayersTab(players);
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

        if (mc != null && mc.getSingleplayerServer() != null) {
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

        BurrowConfig cfg = BurrowClient.config();
        int viewX = this.contentX + 2;
        int viewY = this.contentY + 2;
        int viewW = this.contentW - 4;
        int viewH = CONTENT_H - 4;

        BurrowUi.ScrollContainer scroller = new BurrowUi.ScrollContainer(
                viewX, viewY, viewW, viewH, this.scrollFriends, v -> this.scrollFriends = v);

        int innerX = this.contentX + 6;
        int innerW = this.contentW - 15;
        int y = viewY + 4;

        for (FriendsState.Friend f : friends) {
            boolean expanded = f.uuid() != null && f.uuid().equals(this.expandedFriend);
            FriendsState.WorldInvite worldInv = BurrowClient.friends().worldInviteFrom(f.uuid());
            boolean requestedInvite = BurrowClient.friends().hasInviteRequestFrom(f.uuid());
            boolean fav = cfg != null && cfg.isFavorite(f.uuid());

            scroller.addChild(new FriendCardWidget(
                    innerX, y, innerW, 30, f, worldInv, requestedInvite, expanded,
                    () -> {
                        this.expandedFriend = expanded ? null : f.uuid();
                        rebuildWidgets();
                    }));

            if (f.uuid() != null) {
                scroller.addChild(new BurrowUi.StarButton(
                        innerX + innerW - 29, y + 8, fav,
                        () -> {
                            if (cfg != null) {
                                cfg.toggleFavorite(f.uuid());
                                BurrowClient.friends().notifyChanged();
                                rebuildWidgets();
                            }
                        }));
            }

            y += 30;
            if (expanded) {
                addFriendDrawer(scroller, f, worldInv, requestedInvite, innerX, y, innerW);
                y += 25;
            }
            y += 4;
        }

        scroller.setContentHeight(y - viewY + 2);
        addRenderableWidget(scroller);
    }

    private void addFriendDrawer(
            BurrowUi.ScrollContainer scroller,
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
            boolean mismatch = f.hosting() && f.isVersionMismatch();
            boolean vfpOk = mismatch && ViaFabricPlusCompat.canTranslate(f.gameVersion());
            boolean blockedByVersion = mismatch && !vfpOk;
            Button acceptBtn = Button.builder(
                            Component.literal("Accept Invite").withColor(blockedByVersion ? BurrowUi.TEXT_MUTED : 0xFF7AE89B),
                            b -> JoinFlow.beginByHostUuid(worldInv.host()))
                    .bounds(curX, btnY, 86, 18).build();
            if (blockedByVersion) {
                acceptBtn.active = false;
                acceptBtn.setTooltip(Tooltip.create(Component.literal(
                        "Hosted on Minecraft " + f.gameVersion() + " (you're on " + BurrowClient.minecraftVersion() + ")")));
            } else if (vfpOk) {
                acceptBtn.setTooltip(Tooltip.create(Component.literal(
                        "Hosted on Minecraft " + f.gameVersion() + " — connecting via ViaFabricPlus")));
            }
            scroller.addChild(acceptBtn);
            curX += 86 + gap;

            scroller.addChild(Button.builder(Component.literal("Decline"), b -> {
                BurrowClient.friends().dismissInvite(worldInv.host());
                rebuildWidgets();
            }).bounds(curX, btnY, 54, 18).build());
        }
        // 2. Incoming invite request from this friend -> Invite to World (auto-hosts!) + Dismiss
        else if (requestedInvite) {
            Button inviteBtn = Button.builder(
                            Component.literal("Invite to World").withColor(inSingleplayer ? 0xFF7AE89B : BurrowUi.TEXT_MUTED),
                            b -> {
                                HostSession.inviteFriend(f.uuid());
                                rebuildWidgets();
                            })
                    .bounds(curX, btnY, 96, 18).build();
            if (!inSingleplayer) {
                inviteBtn.active = false;
                inviteBtn.setTooltip(Tooltip.create(Component.literal("Enter a singleplayer world to invite friends.")));
            }
            scroller.addChild(inviteBtn);
            curX += 96 + gap;

            scroller.addChild(Button.builder(Component.literal("Dismiss"), b -> {
                BurrowClient.friends().dismissInviteRequest(f.uuid());
                rebuildWidgets();
            }).bounds(curX, btnY, 54, 18).build());
        }
        // 3. Normal context actions
        else if (f.isOnline()) {
            if (f.hosting()) {
                boolean mismatch = f.isVersionMismatch();
                boolean vfpOk = mismatch && ViaFabricPlusCompat.canTranslate(f.gameVersion());
                boolean canJoin = !f.locked() && (!mismatch || vfpOk);
                Button joinBtn = Button.builder(
                                Component.literal("Join World").withColor(canJoin ? 0xFF7AE89B : BurrowUi.TEXT_MUTED),
                                b -> JoinFlow.begin(f))
                        .bounds(curX, btnY, 76, 18).build();
                if (!canJoin) {
                    joinBtn.active = false;
                    String reason = f.locked()
                            ? "This world is currently locked."
                            : "Hosted on Minecraft " + f.gameVersion() + " (you're on " + BurrowClient.minecraftVersion() + ")";
                    joinBtn.setTooltip(Tooltip.create(Component.literal(reason)));
                } else if (vfpOk) {
                    joinBtn.setTooltip(Tooltip.create(Component.literal(
                            "Hosted on Minecraft " + f.gameVersion() + " — connecting via ViaFabricPlus")));
                }
                scroller.addChild(joinBtn);
                curX += 76 + gap;

                addInviteButton(scroller, f, inSingleplayer, curX, btnY);
            } else if (f.hasJoinableServer()) {
                scroller.addChild(Button.builder(
                                Component.literal("Join Server").withColor(0xFF7CC8FF),
                                b -> JoinFlow.joinExternalServer(f.serverName(), f.serverIp()))
                        .bounds(curX, btnY, 78, 18).build());
                curX += 78 + gap;

                addInviteButton(scroller, f, inSingleplayer, curX, btnY);
            } else {
                // Invite on the left, Request Invite on the right
                addInviteButton(scroller, f, inSingleplayer, curX, btnY);
                curX += 54 + gap;

                Button reqBtn = Button.builder(
                                Component.literal("Request Invite"),
                                b -> {
                                    if (BurrowClient.connection() != null && f.uuid() != null) {
                                        BurrowClient.connection().requestInvite(f.uuid());
                                    }
                                    b.active = false;
                                    b.setMessage(Component.literal("Requested"));
                                })
                        .bounds(curX, btnY, 92, 18).build();
                if (!f.isInGame()) {
                    reqBtn.active = false;
                    reqBtn.setTooltip(Tooltip.create(Component.literal("Not in a world right now.")));
                }
                scroller.addChild(reqBtn);
            }
        }

        // Right-aligned Remove Friend button
        int remW = 54;
        scroller.addChild(Button.builder(
                        Component.literal("Remove").withColor(0xFFFF8A8A),
                        b -> {
                            if (this.minecraft != null) {
                                this.minecraft.gui.setScreen(new RemoveFriendScreen(this, f));
                            }
                        })
                .bounds(x + w - remW - 6, btnY, remW, 18).build());
    }

    private void addInviteButton(
            BurrowUi.ScrollContainer scroller,
            FriendsState.Friend f,
            boolean inSingleplayer,
            int x,
            int y
    ) {
        Button inviteBtn = Button.builder(
                        Component.literal("Invite"),
                        b -> {
                            if (HostSession.inviteFriend(f.uuid())) {
                                b.active = false;
                                b.setMessage(Component.literal("Invited"));
                            }
                        })
                .bounds(x, y, 54, 18).build();
        if (!inSingleplayer) {
            inviteBtn.active = false;
            inviteBtn.setTooltip(Tooltip.create(Component.literal("Enter a singleplayer world to invite friends.")));
        }
        scroller.addChild(inviteBtn);
    }

    private record WorldEntry(
            UUID host,
            String hostName,
            String subtitle,
            String badge,
            int badgeColor,
            boolean locked,
            boolean versionMismatch,
            boolean vfpCompatible,
            String gameVersion,
            boolean favored
    ) {}

    private void initPlayersTab(List<PlayerCandidate> players) {
        if (players.isEmpty()) {
            this.emptyMessage = (this.minecraft == null || this.minecraft.getConnection() == null)
                    ? "Join a world or server to see current & recent players."
                    : "No other players here or recently seen.";
            return;
        }

        FriendsState fs = BurrowClient.friends();
        BurrowConfig cfg = BurrowClient.config();

        List<PlayerCandidate> live = new ArrayList<>();
        List<PlayerCandidate> recent = new ArrayList<>();
        for (PlayerCandidate p : players) {
            if (p.onlineNow()) {
                live.add(p);
            } else {
                recent.add(p);
            }
        }

        live.sort(Comparator
                .comparingInt((PlayerCandidate p) -> {
                    boolean isFriend = fs.isFriend(p.uuid()) || fs.isFriendByName(p.name());
                    boolean isFav = isFriend && cfg != null && cfg.isFavorite(p.uuid());
                    boolean hasMod = isFriend || fs.hasBurrowAccount(p.uuid(), p.name());
                    if (isFav) return 0;
                    if (hasMod && !isFriend) return 1;
                    if (isFriend) return 2;
                    return 3;
                })
                .thenComparing(p -> p.name() == null ? "" : p.name().toLowerCase()));

        // Recent players keep most-recently-seen order, with Burrow users slightly prioritized?
        // Actually keeping chronological most-recent-first order matches "most recent 50 players you saw".

        int viewX = this.contentX + 2;
        int viewY = this.contentY + 2;
        int viewW = this.contentW - 4;
        int viewH = CONTENT_H - 4;

        BurrowUi.ScrollContainer scroller = new BurrowUi.ScrollContainer(
                viewX, viewY, viewW, viewH, this.scrollPlayers, v -> this.scrollPlayers = v);

        List<CardDisplay> cards = new ArrayList<>();
        List<SectionLabel> sections = new ArrayList<>();
        List<FriendsState.Request> reqs = fs.requests();

        int innerX = this.contentX + 6;
        int innerW = this.contentW - 15;
        int y = viewY + 4;

        if (!live.isEmpty()) {
            if (!recent.isEmpty()) {
                sections.add(new SectionLabel("In This World / Server", innerX + 2, y + 1));
                y += 13;
            }
            for (PlayerCandidate p : live) {
                y = addPlayerCard(scroller, cards, fs, cfg, reqs, p, innerX, y, innerW);
            }
        }

        if (!recent.isEmpty()) {
            if (!live.isEmpty()) {
                y += 2;
            }
            sections.add(new SectionLabel("Recently Seen (" + recent.size() + ")", innerX + 2, y + 1));
            y += 13;
            for (PlayerCandidate p : recent) {
                y = addPlayerCard(scroller, cards, fs, cfg, reqs, p, innerX, y, innerW);
            }
        }

        scroller.setBackgroundRenderer((g, mx, my, scrollPx) -> {
            for (SectionLabel sec : sections) {
                g.text(this.font, sec.text(), sec.x(), sec.y() - scrollPx, BurrowUi.TEXT_MUTED, false);
            }
            drawCardDisplays(g, cards, scrollPx);
        });
        scroller.setContentHeight(y - viewY + 2);
        addRenderableWidget(scroller);
    }

    private int addPlayerCard(
            BurrowUi.ScrollContainer scroller,
            List<CardDisplay> cards,
            FriendsState fs,
            @Nullable BurrowConfig cfg,
            List<FriendsState.Request> reqs,
            PlayerCandidate p,
            int innerX,
            int y,
            int innerW
    ) {
        UUID uuid = p.uuid();
        String name = p.name() == null ? "?" : p.name();
        boolean isFriend = fs.isFriend(uuid) || fs.isFriendByName(name);
        boolean hasMod = isFriend || fs.hasBurrowAccount(uuid, name);

        FriendsState.Request matchingReq = null;
        for (FriendsState.Request r : reqs) {
            if ((uuid != null && uuid.equals(r.uuid()))
                    || (r.name() != null && r.name().equalsIgnoreCase(name))) {
                matchingReq = r;
                break;
            }
        }

        String subtitle;
        boolean muted = !hasMod;
        if (isFriend) {
            subtitle = "Already friends";
        } else if (matchingReq != null) {
            subtitle = matchingReq.outgoing() ? "Friend request sent" : "Sent you a friend request";
        } else if (!p.onlineNow()) {
            String where = (p.recentSource() != null && !p.recentSource().isBlank())
                    ? "Seen in " + p.recentSource()
                    : "Recently seen";
            subtitle = hasMod ? ("Uses Burrow · " + where) : where;
        } else if (hasMod) {
            subtitle = "Uses Burrow";
        } else {
            subtitle = "Doesn't have Burrow";
        }

        cards.add(new CardDisplay(uuid, name, subtitle, null, 0, muted, innerX, y, innerW, 30));

        int btnW = 58;
        int btnX = innerX + innerW - btnW - 6;
        if (isFriend) {
            if (uuid != null && cfg != null) {
                scroller.addChild(new BurrowUi.StarButton(
                        btnX - 17, y + 8, cfg.isFavorite(uuid),
                        () -> {
                            cfg.toggleFavorite(uuid);
                            BurrowClient.friends().notifyChanged();
                            rebuildWidgets();
                        }));
            }
            Button b = Button.builder(Component.literal("Friends"), btn -> {})
                    .bounds(btnX, y + 6, btnW, 18).build();
            b.active = false;
            scroller.addChild(b);
        } else if (matchingReq != null && !matchingReq.outgoing()) {
            UUID reqUuid = matchingReq.uuid();
            scroller.addChild(Button.builder(Component.literal("Accept").withColor(0xFF7AE89B), btn -> {
                if (BurrowClient.connection() != null && reqUuid != null) {
                    BurrowClient.connection().acceptRequest(reqUuid);
                }
            }).bounds(btnX, y + 6, btnW, 18).build());
        } else if (matchingReq != null) {
            Button b = Button.builder(Component.literal("Pending"), btn -> {})
                    .bounds(btnX, y + 6, btnW, 18).build();
            b.active = false;
            scroller.addChild(b);
        } else if (hasMod) {
            scroller.addChild(Button.builder(Component.literal("+ Add").withColor(0xFF7AE89B), btn -> {
                if (BurrowClient.connection() != null) {
                    BurrowClient.connection().addFriend(name);
                }
                btn.active = false;
                btn.setMessage(Component.literal("Sent"));
            }).bounds(btnX, y + 6, btnW, 18).build());
        } else {
            Button b = Button.builder(Component.literal("No Mod"), btn -> {})
                    .bounds(btnX, y + 6, btnW, 18).build();
            b.active = false;
            b.setTooltip(Tooltip.create(Component.literal("This player doesn't have Burrow installed yet.")));
            scroller.addChild(b);
        }
        return y + 34;
    }

    private List<PlayerCandidate> collectPlayersAndRecent() {
        List<PlayerCandidate> out = new ArrayList<>();
        Set<UUID> seenUuids = new HashSet<>();
        Set<String> seenNames = new HashSet<>();

        UUID myProfileId = (this.minecraft != null && this.minecraft.getUser() != null)
                ? this.minecraft.getUser().getProfileId()
                : BurrowClient.friends().selfUuid();
        String myName = (this.minecraft != null && this.minecraft.getUser() != null)
                ? this.minecraft.getUser().getName()
                : BurrowClient.friends().selfName();

        // 1. Currently online players in the active world/server
        if (this.minecraft != null && this.minecraft.getConnection() != null) {
            ClientPacketListener conn = this.minecraft.getConnection();
            for (PlayerInfo info : conn.getListedOnlinePlayers()) {
                GameProfile prof = info.getProfile();
                if (prof == null || prof.name() == null || prof.name().isBlank()) continue;
                if (myProfileId != null && myProfileId.equals(prof.id())) continue;
                if (myName != null && myName.equalsIgnoreCase(prof.name())) continue;
                out.add(new PlayerCandidate(prof.id(), prof.name(), true, "", System.currentTimeMillis()));
                if (prof.id() != null) seenUuids.add(prof.id());
                seenNames.add(prof.name().toLowerCase());
            }
        }

        // 2. Persisted recent 50 non-friend players (across worlds & external servers)
        BurrowConfig cfg = BurrowClient.config();
        FriendsState fs = BurrowClient.friends();
        if (cfg != null) {
            for (BurrowConfig.RecentPlayer rp : cfg.recentPlayersSnapshot()) {
                if (rp == null || rp.name == null || rp.name.isBlank()) continue;
                UUID id = rp.parsedUuid();
                if (myProfileId != null && myProfileId.equals(id)) continue;
                if (myName != null && myName.equalsIgnoreCase(rp.name)) continue;
                if (fs.isFriend(id) || fs.isFriendByName(rp.name)) continue;
                if ((id != null && seenUuids.contains(id)) || seenNames.contains(rp.name.toLowerCase())) continue;
                out.add(new PlayerCandidate(id, rp.name, false, rp.source == null ? "" : rp.source, rp.lastSeenMs));
                if (id != null) seenUuids.add(id);
                seenNames.add(rp.name.toLowerCase());
            }
        }
        return out;
    }

    private List<WorldEntry> collectDiscoverableWorlds() {
        List<WorldEntry> items = new ArrayList<>();
        FriendsState fs = BurrowClient.friends();
        BurrowConfig cfg = BurrowClient.config();

        // Build set of favorited friend names to float FoF worlds via favorites to the top
        Set<String> favNamesLower = new HashSet<>();
        if (cfg != null) {
            for (FriendsState.Friend f : fs.friends()) {
                if (cfg.isFavorite(f.uuid()) && f.name() != null) {
                    favNamesLower.add(f.name().toLowerCase());
                }
            }
        }

        // Include world invites from non-friends (friend invites already surface right in Friends!)
        for (FriendsState.WorldInvite inv : fs.worldInvites()) {
            if (fs.isFriend(inv.host())) {
                continue;
            }
            boolean fav = (cfg != null && cfg.isFavorite(inv.host()))
                    || (inv.invitedByName() != null && favNamesLower.contains(inv.invitedByName().toLowerCase()));
            String sub = inv.worldName() + " · Invited by " + inv.invitedByName();
            items.add(new WorldEntry(inv.host(), inv.hostName(), sub, "Invited", BurrowUi.DOT_NOTIFY,
                    false, false, false, "", fav));
        }

        for (FriendsState.DiscoverableWorld w : fs.discoverableWorlds()) {
            boolean alreadyListed = items.stream().anyMatch(i -> w.host().equals(i.host()));
            if (alreadyListed) continue;
            boolean mismatch = w.isVersionMismatch();
            boolean vfpOk = mismatch && ViaFabricPlusCompat.canTranslate(w.gameVersion());
            boolean fav = (cfg != null && cfg.isFavorite(w.host()))
                    || (!w.viaName().isEmpty() && favNamesLower.contains(w.viaName().toLowerCase()));
            String badge;
            int color;
            if (mismatch) {
                badge = "MC " + w.gameVersion();
                color = vfpOk ? BurrowUi.ROLE_VISITOR : BurrowUi.DOT_LOCKED;
            } else if ("friend_of_friend".equals(w.source()) && !w.viaName().isEmpty()) {
                badge = "via " + w.viaName();
                color = BurrowUi.ROLE_VISITOR;
            } else {
                badge = "World";
                color = BurrowUi.ROLE_HOST;
            }
            String count = w.maxPlayers() > 0 ? " (" + w.playerCount() + "/" + w.maxPlayers() + ")" : "";
            String sub = w.worldName() + count + (w.locked() ? " · Locked" : "");
            items.add(new WorldEntry(w.host(), w.hostName(), sub, badge, color,
                    w.locked(), mismatch, vfpOk, w.gameVersion(), fav));
        }

        items.sort(Comparator
                .comparing((WorldEntry w) -> !w.favored())
                .thenComparing(w -> w.hostName().toLowerCase()));
        return items;
    }

    private void initWorldsTab(List<WorldEntry> items) {
        if (items.isEmpty()) {
            this.emptyMessage = "No joinable worlds available right now.";
            return;
        }

        int viewX = this.contentX + 2;
        int viewY = this.contentY + 2;
        int viewW = this.contentW - 4;
        int viewH = CONTENT_H - 4;

        BurrowUi.ScrollContainer scroller = new BurrowUi.ScrollContainer(
                viewX, viewY, viewW, viewH, this.scrollWorlds, v -> this.scrollWorlds = v);

        List<CardDisplay> cards = new ArrayList<>();
        int innerX = this.contentX + 6;
        int innerW = this.contentW - 15;
        int y = viewY + 4;

        for (WorldEntry item : items) {
            cards.add(new CardDisplay(
                    item.host(), item.hostName(), item.subtitle(),
                    item.badge(), item.badgeColor(), false,
                    innerX, y, innerW, 30));
            boolean canJoin = !item.locked() && (!item.versionMismatch() || item.vfpCompatible());
            Button joinBtn = Button.builder(
                            Component.literal(item.locked() ? "Locked" : "Join")
                                    .withColor(canJoin ? 0xFF7AE89B : BurrowUi.TEXT_MUTED),
                            b -> JoinFlow.beginByHostUuid(item.host()))
                    .bounds(innerX + innerW - 56, y + 6, 50, 18).build();
            joinBtn.active = canJoin;
            if (item.versionMismatch() && !item.vfpCompatible()) {
                joinBtn.setTooltip(Tooltip.create(Component.literal(
                        "Hosted on Minecraft " + item.gameVersion() + " (you're on " + BurrowClient.minecraftVersion() + ")")));
            } else if (item.vfpCompatible()) {
                joinBtn.setTooltip(Tooltip.create(Component.literal(
                        "Hosted on Minecraft " + item.gameVersion() + " — connecting via ViaFabricPlus")));
            }
            scroller.addChild(joinBtn);
            y += 34;
        }

        scroller.setBackgroundRenderer((g, mx, my, scrollPx) -> drawCardDisplays(g, cards, scrollPx));
        scroller.setContentHeight(y - viewY + 2);
        addRenderableWidget(scroller);
    }

    private void drawCardDisplays(GuiGraphicsExtractor g, List<CardDisplay> cards, int scrollPx) {
        for (CardDisplay c : cards) {
            int cy = c.y() - scrollPx;
            if (cy + c.h() < this.contentY || cy > this.contentY + CONTENT_H) {
                continue;
            }
            int cardBg = c.muted() ? 0xFF1D1D22 : BurrowUi.CARD_BG;
            int nameColor = c.muted() ? BurrowUi.TEXT_MUTED : BurrowUi.TEXT_PRIMARY;
            int subColor = c.muted() ? 0xFF686872 : BurrowUi.TEXT_SECONDARY;
            g.fill(c.x(), cy, c.x() + c.w(), cy + c.h(), cardBg);
            g.outline(c.x(), cy, c.w(), c.h(), BurrowUi.CARD_BORDER);
            PlayerHeads.draw(g, c.uuid(), c.name(), c.x() + 5, cy + 5, 20);
            if (c.muted()) {
                g.fill(c.x() + 5, cy + 5, c.x() + 25, cy + 25, 0x88141418);
            }
            int textX = c.x() + 31;
            g.text(this.font, c.name(), textX, cy + 5, nameColor, !c.muted());
            if (c.badge() != null && !c.badge().isEmpty()) {
                int nameW = this.font.width(c.name());
                BurrowUi.drawTagPill(g, this.font, textX + nameW + 5, cy + 4, c.badge(), c.badgeColor());
            }
            int maxSubW = c.w() - 108;
            g.text(this.font, ellipsize(this.font, c.subtitle(), maxSubW),
                    textX, cy + 17, subColor, false);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);

        BurrowUi.drawModalPanel(g, this.panelX, this.panelY, PANEL_W, PANEL_H);

        g.text(this.font, "Friends", this.contentX, this.panelY + BurrowUi.HEADER_TITLE_Y, BurrowUi.TEXT_PRIMARY, true);
        String flash = BurrowClient.friends().errorFlash();
        this.showingErrorFlash = (flash != null);
        if (flash != null) {
            int maxFlashW = (this.lastConnectionStatus == BurrowConnection.Status.ERROR
                    || this.lastConnectionStatus == BurrowConnection.Status.OFFLINE) ? 228 : 276;
            g.text(this.font, ellipsize(this.font, "! " + flash, maxFlashW),
                    this.contentX, this.panelY + BurrowUi.HEADER_SUB_Y, 0xFFFF9977, false);
        } else {
            g.text(this.font, statusText(), this.contentX, this.panelY + BurrowUi.HEADER_SUB_Y, BurrowUi.TEXT_SECONDARY, false);
        }

        BurrowUi.drawInsetBox(g, this.contentX, this.contentY, this.contentW, CONTENT_H);

        if (this.emptyMessage != null) {
            g.centeredText(this.font, this.emptyMessage,
                    this.contentX + this.contentW / 2,
                    this.contentY + CONTENT_H / 2 - 4,
                    BurrowUi.TEXT_MUTED);
        }
    }

    private List<FriendsState.Friend> sortedFriends() {
        BurrowConfig cfg = BurrowClient.config();
        List<FriendsState.Friend> list = new ArrayList<>(BurrowClient.friends().friends());
        list.sort(Comparator
                .comparingInt((FriendsState.Friend f) -> {
                    boolean hasNotif = BurrowClient.friends().worldInviteFrom(f.uuid()) != null
                            || BurrowClient.friends().hasInviteRequestFrom(f.uuid());
                    return hasNotif ? 0 : 1;
                })
                .thenComparingInt(f -> (cfg != null && cfg.isFavorite(f.uuid())) ? 0 : 1)
                .thenComparingInt(f -> {
                    if (f.hosting()) return 0;
                    if (f.hasJoinableServer()) return 1;
                    if (f.isInGame()) return 2;
                    if (f.isOnline()) return 3;
                    return 4;
                })
                .thenComparing(f -> f.name() == null ? "" : f.name().toLowerCase()));
        return list;
    }

    private void switchTab(Tab next) {
        this.tab = next;
        this.expandedFriend = null;
        if (next == Tab.PLAYERS) {
            this.checkedPlayers = false;
        }
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
        BurrowConnection conn = BurrowClient.connection();
        BurrowConnection.Status currentStatus = conn == null ? BurrowConnection.Status.OFFLINE : conn.status();
        if (currentStatus != this.lastConnectionStatus
                || (this.showingErrorFlash && BurrowClient.friends().errorFlash() == null)) {
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
        boolean dnd = BurrowClient.config() != null && BurrowClient.config().doNotDisturb;
        return switch (status) {
            case CONNECTED -> "Connected as " + (self == null ? "?" : self) + (dnd ? " · Do Not Disturb" : "");
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

    /** Sleek dark friend card with skin head, corner status dot (blue for invites/requests, coral for DND), and inline drawer. */
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

            // Player skin head + corner status dot
            PlayerHeads.draw(g, this.friend.uuid(), this.friend.name(), x + 5, y + 5, 20);
            int dotColor;
            if (this.worldInvite != null || this.requestedInvite) {
                dotColor = BurrowUi.DOT_NOTIFY;
            } else if (this.friend.hosting()) {
                dotColor = this.friend.locked() ? BurrowUi.DOT_LOCKED : BurrowUi.DOT_LIVE;
            } else if (this.friend.isDnd()) {
                dotColor = BurrowUi.DOT_DND;
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

            // Compact native-scale tag pill next to name
            int nameW = font.width(this.friend.name());
            if (this.worldInvite != null) {
                BurrowUi.drawTagPill(g, font, textX + nameW + 5, y + 4, "Invited You", BurrowUi.DOT_NOTIFY);
            } else if (this.requestedInvite) {
                BurrowUi.drawTagPill(g, font, textX + nameW + 5, y + 4, "Wants to Join", BurrowUi.DOT_NOTIFY);
            } else if (this.friend.hosting()) {
                boolean mismatch = this.friend.isVersionMismatch();
                boolean vfpOk = mismatch && ViaFabricPlusCompat.canTranslate(this.friend.gameVersion());
                String badge = mismatch
                        ? "MC " + this.friend.gameVersion()
                        : (this.friend.locked() ? "Locked" : "Hosting");
                int color = this.friend.locked()
                        ? BurrowUi.DOT_LOCKED
                        : (mismatch ? (vfpOk ? BurrowUi.ROLE_VISITOR : BurrowUi.DOT_LOCKED) : BurrowUi.ROLE_MEMBER);
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
                    : (this.friend.isDnd() ? BurrowUi.DOT_DND
                    : (this.friend.isOnline() ? BurrowUi.TEXT_SECONDARY : BurrowUi.TEXT_MUTED));
            g.text(font, ellipsize(font, subtitle, w - 68), textX, y + 17, subColor, false);

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

    /** Same-size dedicated modal for adding a friend by username and managing requests (smooth-scrolled). */
    static class FriendRequestsScreen extends Screen {
        private static final int MODAL_W = BurrowUi.PANEL_W;
        private static final int MODAL_H = BurrowUi.PANEL_H;
        private static final int PAD = BurrowUi.PAD;
        private static final int INSET_TOP = BurrowUi.TABS_Y;
        private static final int INSET_H = 155;

        private record RequestRowDisplay(
                UUID uuid,
                String name,
                String subtitle,
                int x, int y, int w, int h
        ) {}

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
        private double scrollY = 0.0;
        private String typedName = "";
        private @Nullable EditBox nameBox;
        private @Nullable String emptyMsg;
        private boolean showingFlash = false;

        FriendRequestsScreen(Screen parent) {
            super(Component.literal("Add Friend & Requests"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            this.emptyMsg = null;
            this.nameBox = null;
            this.modalX = (this.width - MODAL_W) / 2;
            this.modalY = Math.max(2, (this.height - MODAL_H) / 2);
            this.innerX = this.modalX + PAD;
            this.innerW = MODAL_W - PAD * 2;

            int boxX = this.innerX + 8;
            int boxW = this.innerW - 16;
            int insetTop = this.modalY + INSET_TOP;
            int sendW = 54;

            this.nameBox = new EditBox(this.font, boxX, insetTop + 8, boxW - sendW - 4, 18,
                    Component.literal("Add friend"));
            this.nameBox.setMaxLength(32);
            this.nameBox.setHint(Component.literal("Enter username..."));
            this.nameBox.setValue(this.typedName);
            addRenderableWidget(this.nameBox);
            addRenderableWidget(Button.builder(Component.literal("Send"), b -> sendRequest())
                    .bounds(boxX + boxW - sendW, insetTop + 7, sendW, 20).build());
            setInitialFocus(this.nameBox);

            List<FriendsState.Request> requests = BurrowClient.friends().requests();
            if (requests.isEmpty()) {
                this.emptyMsg = "No pending friend requests.";
            } else {
                int viewX = this.innerX + 2;
                int viewY = insetTop + 32;
                int viewW = this.innerW - 4;
                int viewH = INSET_H - 34;

                BurrowUi.ScrollContainer scroller = new BurrowUi.ScrollContainer(
                        viewX, viewY, viewW, viewH, this.scrollY, v -> this.scrollY = v);
                List<RequestRowDisplay> rows = new ArrayList<>();

                int rowX = this.innerX + 6;
                int rowW = this.innerW - 15;
                int y = viewY + 2;
                for (FriendsState.Request r : requests) {
                    if (r.outgoing()) {
                        rows.add(new RequestRowDisplay(r.uuid(), r.name(), "Outgoing request",
                                rowX, y, rowW, 28));
                        scroller.addChild(Button.builder(Component.literal("Cancel"), b -> {
                            if (BurrowClient.connection() != null) {
                                BurrowClient.connection().declineRequest(r.uuid());
                            }
                        }).bounds(rowX + rowW - 58, y + 5, 52, 18).build());
                    } else {
                        rows.add(new RequestRowDisplay(r.uuid(), r.name(), "Incoming request",
                                rowX, y, rowW, 28));
                        scroller.addChild(Button.builder(Component.literal("Accept").withColor(0xFF7AE89B), b -> {
                            if (BurrowClient.connection() != null) {
                                BurrowClient.connection().acceptRequest(r.uuid());
                            }
                        }).bounds(rowX + rowW - 110, y + 5, 50, 18).build());
                        scroller.addChild(Button.builder(Component.literal("Decline").withColor(0xFFFF8A8A), b -> {
                            if (BurrowClient.connection() != null) {
                                BurrowClient.connection().declineRequest(r.uuid());
                            }
                        }).bounds(rowX + rowW - 56, y + 5, 50, 18).build());
                    }
                    y += 32;
                }

                scroller.setBackgroundRenderer((g, mx, my, scrollPx) -> {
                    for (RequestRowDisplay c : rows) {
                        int cy = c.y() - scrollPx;
                        g.fill(c.x(), cy, c.x() + c.w(), cy + c.h(), BurrowUi.CARD_BG);
                        g.outline(c.x(), cy, c.w(), c.h(), BurrowUi.CARD_BORDER);
                        PlayerHeads.draw(g, c.uuid(), c.name(), c.x() + 5, cy + 4, 20);
                        g.text(this.font, c.name(), c.x() + 31, cy + 5, BurrowUi.TEXT_PRIMARY, true);
                        g.text(this.font, c.subtitle(), c.x() + 31, cy + 16, BurrowUi.TEXT_SECONDARY, false);
                    }
                });
                scroller.setContentHeight(y - viewY + 2);
                addRenderableWidget(scroller);
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
                g.text(this.font, "Add by username or manage pending requests",
                        this.innerX, this.modalY + BurrowUi.HEADER_SUB_Y, BurrowUi.TEXT_SECONDARY, false);
            }

            BurrowUi.drawInsetBox(g, this.innerX, this.modalY + INSET_TOP, this.innerW, INSET_H);

            if (this.emptyMsg != null) {
                g.centeredText(this.font, this.emptyMsg,
                        this.modalX + MODAL_W / 2, this.modalY + INSET_TOP + 88, BurrowUi.TEXT_MUTED);
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
                    && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
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
        private static final int MODAL_H = 105;
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
            g.text(this.font, "Remove Friend", this.modalX + 10, this.modalY + 11, BurrowUi.TEXT_PRIMARY, true);
            PlayerHeads.draw(g, this.friend.uuid(), this.friend.name(), this.modalX + 12, this.modalY + 35, 20);
            g.text(this.font, "Remove " + this.friend.name() + "?",
                    this.modalX + 40, this.modalY + 41, BurrowUi.TEXT_SECONDARY, false);
        }

        @Override
        public void onClose() {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(this.parent);
            }
        }
    }
}
