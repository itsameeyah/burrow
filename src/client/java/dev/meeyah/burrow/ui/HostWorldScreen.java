package dev.meeyah.burrow.ui;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.session.HostPermissions;
import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.session.HostWorldState;
import dev.meeyah.burrow.state.FriendsState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Vanilla-styled "Host World" / "Host Settings" control panel.
 */
public class HostWorldScreen extends Screen {
    private static final int PANEL_W = BurrowUi.PANEL_W;
    private static final int PANEL_H = BurrowUi.PANEL_H;
    private static final int PAD_X = BurrowUi.PAD;
    private static final int TABS_Y = BurrowUi.TABS_Y;
    private static final int TAB_H = BurrowUi.TAB_H;
    private static final int CONTENT_Y = BurrowUi.CONTENT_Y;
    private static final int CONTENT_H = BurrowUi.CONTENT_H;
    private static final int FOOTER_Y = BurrowUi.FOOTER_Y;

    private enum Tab { SETTINGS, PLAYERS, BANS }

    private final @Nullable Screen parent;
    private Tab tab = Tab.SETTINGS;
    private @Nullable UUID expandedPlayer = null;
    private int listPage = 0;
    private int tickCounter = 0;
    private int lastSeenPlayerCount = -1;

    private int panelX;
    private int panelY;
    private int contentX;
    private int contentY;
    private int contentW;
    private final List<RowLabel> settingLabels = new ArrayList<>();
    private final List<BanRowDisplay> banRows = new ArrayList<>();
    private @Nullable String playersSubHeader;
    private @Nullable String emptyMessage;

    private record RowLabel(String text, int x, int y) {}
    private record BanRowDisplay(UUID uuid, String name, int x, int y, int w, int h) {}

    public HostWorldScreen(@Nullable Screen parent) {
        super(Component.literal("Host World"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.settingLabels.clear();
        this.banRows.clear();
        this.playersSubHeader = null;
        this.emptyMessage = null;

        this.panelX = (this.width - PANEL_W) / 2;
        this.panelY = Math.max(4, (this.height - PANEL_H) / 2);
        this.contentX = this.panelX + PAD_X;
        this.contentY = this.panelY + CONTENT_Y;
        this.contentW = PANEL_W - PAD_X * 2;

        IntegratedServer server = this.minecraft.getSingleplayerServer();
        if (server == null) {
            this.emptyMessage = "Hosting is only available inside a singleplayer world.";
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                    .bounds(this.contentX + this.contentW - 84, this.panelY + FOOTER_Y, 84, 20).build());
            return;
        }

        HostWorldState state = HostSession.worldState(server);
        if (state == null) {
            onClose();
            return;
        }

        boolean hosting = HostSession.isHosting();
        List<ServerPlayer> players = List.copyOf(server.getPlayerList().getPlayers());
        int playerCount = Math.max(1, players.size());
        this.lastSeenPlayerCount = playerCount;
        Map<UUID, String> banned = state.bannedSnapshot();

        // ----- Vanilla Tabs (flush with inset box) -----
        int tabX = this.contentX;
        int tabY = this.panelY + TABS_Y;
        int tabGap = 2;
        int tabW = (this.contentW - tabGap * 2) / 3;
        int lastTabW = this.contentW - (tabW + tabGap) * 2;
        addRenderableWidget(new BurrowUi.TabButton(
                tabX, tabY, tabW, TAB_H,
                Component.literal("Settings"),
                this.tab == Tab.SETTINGS,
                () -> switchTab(Tab.SETTINGS)));
        addRenderableWidget(new BurrowUi.TabButton(
                tabX + tabW + tabGap, tabY, tabW, TAB_H,
                Component.literal("Players (" + playerCount + "/" + state.maxPlayers() + ")"),
                this.tab == Tab.PLAYERS,
                () -> switchTab(Tab.PLAYERS)));
        addRenderableWidget(new BurrowUi.TabButton(
                tabX + (tabW + tabGap) * 2, tabY, lastTabW, TAB_H,
                Component.literal("Bans (" + banned.size() + ")"),
                this.tab == Tab.BANS,
                () -> switchTab(Tab.BANS)));

        // ----- Tab Content -----
        switch (this.tab) {
            case SETTINGS -> initSettingsTab(state, hosting);
            case PLAYERS -> initPlayersTab(server, state, players, hosting);
            case BANS -> initBansTab(server, banned);
        }

        // ----- Footer Bar -----
        int footerY = this.panelY + FOOTER_Y;
        Component hostBtnLabel = hosting
                ? Component.literal("Stop Hosting").withColor(0xFFFF8A8A)
                : Component.literal("Start Hosting").withColor(0xFF7AE89B);
        addRenderableWidget(Button.builder(hostBtnLabel, b -> {
            if (HostSession.isHosting()) {
                HostSession.stop();
            } else {
                HostSession.start();
            }
            rebuildWidgets();
        }).bounds(this.contentX, footerY, 118, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(this.contentX + this.contentW - 84, footerY, 84, 20).build());
    }

    private void initSettingsTab(HostWorldState state, boolean hosting) {
        // 6 rows x 18px + 5 x 3px gaps = 123px centered inside 136px -> 6px top & 7px bottom padding
        int labelX = this.contentX + 10;
        int ctrlW = 152;
        int ctrlX = this.contentX + this.contentW - ctrlW - 8;
        int rowY = this.contentY + 6;
        int rowStep = 21;
        int btnH = 18;

        this.settingLabels.add(new RowLabel("Who Can Join", labelX, rowY + 5));
        addRenderableWidget(CycleButton.<HostWorldState.AccessMode>builder(
                        m -> Component.literal(m.label()), state.access())
                .withValues(HostWorldState.AccessMode.values())
                .displayOnlyValue()
                .withTooltip(m -> Tooltip.create(Component.literal(accessTooltip(m))))
                .create(ctrlX, rowY, ctrlW, btnH, Component.literal("Who Can Join"), (btn, val) -> {
                    state.setAccess(val);
                    HostSession.syncState();
                }));
        rowY += rowStep;

        this.settingLabels.add(new RowLabel("Default Role", labelX, rowY + 5));
        addRenderableWidget(CycleButton.<HostWorldState.Role>builder(
                        r -> Component.literal(r.label()), state.defaultRole())
                .withValues(HostWorldState.Role.values())
                .displayOnlyValue()
                .withTooltip(r -> Tooltip.create(Component.literal(roleTooltip(r))))
                .create(ctrlX, rowY, ctrlW, btnH, Component.literal("Default Role"), (btn, val) -> {
                    state.setDefaultRole(val);
                }));
        rowY += rowStep;

        this.settingLabels.add(new RowLabel("Who Can Invite", labelX, rowY + 5));
        addRenderableWidget(CycleButton.<HostWorldState.InvitePerm>builder(
                        p -> Component.literal(p.label()), state.invitePerm())
                .withValues(HostWorldState.InvitePerm.values())
                .displayOnlyValue()
                .withTooltip(p -> Tooltip.create(Component.literal("Controls who can send world invites.")))
                .create(ctrlX, rowY, ctrlW, btnH, Component.literal("Who Can Invite"), (btn, val) -> {
                    state.setInvitePerm(val);
                    HostSession.syncState();
                }));
        rowY += rowStep;

        this.settingLabels.add(new RowLabel("Player Cap", labelX, rowY + 5));
        addRenderableWidget(new PlayerCapSlider(ctrlX, rowY, ctrlW, btnH, state));
        rowY += rowStep;

        this.settingLabels.add(new RowLabel("Mismatched Mods", labelX, rowY + 5));
        boolean allowMismatch = state.allowMismatchedMods();
        Component mismatchLabel = allowMismatch
                ? Component.literal("Allowed").withColor(0xFF7AE89B)
                : Component.literal("Strict").withColor(0xFFFFCC66);
        Button mismatchBtn = Button.builder(mismatchLabel, b -> {
            state.setAllowMismatchedMods(!state.allowMismatchedMods());
            rebuildWidgets();
        }).bounds(ctrlX, rowY, ctrlW, btnH).build();
        mismatchBtn.setTooltip(Tooltip.create(Component.literal(
                allowMismatch
                        ? "Friends can join without matching client-side mods. Switch to Strict if this world uses content mods."
                        : "Enforces matching Fabric mod registries. Friends can still click 'Join Anyway' on mismatch.")));
        addRenderableWidget(mismatchBtn);
        rowY += rowStep;

        this.settingLabels.add(new RowLabel("Join Lock", labelX, rowY + 5));
        Component lockLabel = state.locked()
                ? Component.literal("Locked").withColor(0xFFFFCC66)
                : Component.literal("Unlocked");
        Button lockBtn = Button.builder(lockLabel, b -> {
            state.setLocked(!state.locked());
            HostSession.syncState();
            rebuildWidgets();
        }).bounds(ctrlX, rowY, ctrlW, btnH).build();
        lockBtn.active = hosting;
        lockBtn.setTooltip(Tooltip.create(Component.literal(
                hosting
                        ? "Block new players from joining without kicking anyone already here."
                        : "Start hosting first to lock or unlock joins.")));
        addRenderableWidget(lockBtn);
    }

    private void initPlayersTab(
            IntegratedServer server,
            HostWorldState state,
            List<ServerPlayer> players,
            boolean hosting
    ) {
        List<ServerPlayer> ordered = new ArrayList<>(players);
        ordered.sort((a, b) -> {
            boolean aHost = server.isSingleplayerOwner(a.nameAndId());
            boolean bHost = server.isSingleplayerOwner(b.nameAndId());
            if (aHost != bHost) return aHost ? -1 : 1;
            return a.getScoreboardName().compareToIgnoreCase(b.getScoreboardName());
        });

        if (this.expandedPlayer != null) {
            boolean stillHere = ordered.stream().anyMatch(p -> this.expandedPlayer.equals(p.getUUID()));
            if (!stillHere) {
                this.expandedPlayer = null;
            }
        }

        int innerX = this.contentX + 8;
        int innerW = this.contentW - 16;
        int count = Math.max(1, ordered.size());
        this.playersSubHeader = count + " of " + state.maxPlayers() + " players in world";

        if (hosting) {
            addRenderableWidget(Button.builder(Component.literal("+ Invite"), b -> {
                if (this.minecraft != null) {
                    this.minecraft.gui.setScreen(new InviteFriendScreen(this));
                }
            }).bounds(innerX + innerW - 64, this.contentY + 6, 64, 16).build());
        }

        int perPage = (this.expandedPlayer != null) ? 2 : 3;
        int pages = Math.max(1, (ordered.size() + perPage - 1) / perPage);
        if (this.listPage >= pages) this.listPage = 0;
        int from = this.listPage * perPage;
        int to = Math.min(ordered.size(), from + perPage);

        int y = this.contentY + 26;
        for (ServerPlayer p : ordered.subList(from, to)) {
            UUID uuid = p.getUUID();
            String name = p.getScoreboardName();
            boolean isHost = server.isSingleplayerOwner(p.nameAndId());
            boolean expanded = !isHost && uuid.equals(this.expandedPlayer);

            String roleLabel = isHost ? "Host" : state.roleOf(uuid).label();
            int roleColor = isHost ? BurrowUi.ROLE_HOST : roleColor(state.roleOf(uuid));
            String subtitle = isHost ? "World owner" : state.joinInfoOf(uuid).describe();

            addRenderableWidget(new PlayerCardWidget(
                    innerX, y, innerW, 30,
                    uuid, name, roleLabel, roleColor, subtitle, !isHost, expanded,
                    () -> {
                        this.expandedPlayer = expanded ? null : uuid;
                        rebuildWidgets();
                    }));
            y += 30;

            if (expanded) {
                addExpandedPlayerDrawer(server, state, uuid, name, innerX, y, innerW);
                y += 25;
            }
            y += 4;
        }

        if (pages > 1) {
            addPagerControls(innerX, this.contentY + CONTENT_H - 20, innerW, pages);
        }
    }

    private void addExpandedPlayerDrawer(
            IntegratedServer server,
            HostWorldState state,
            UUID uuid,
            String name,
            int x,
            int y,
            int w
    ) {
        int btnY = y + 3;
        int curX = x + 6;
        int gap = 4;

        HostWorldState.Role current = state.roleOf(uuid);
        CycleButton<HostWorldState.Role> roleBtn = CycleButton.<HostWorldState.Role>builder(
                        r -> Component.literal("Role: " + r.label()), current)
                .withValues(HostWorldState.Role.values())
                .displayOnlyValue()
                .create(curX, btnY, 96, 18, Component.literal("Role"), (btn, val) -> {
                    HostPermissions.setPlayerRole(server, uuid, val);
                    rebuildWidgets();
                });
        addRenderableWidget(roleBtn);
        curX += 96 + gap;

        if (!BurrowClient.friends().isFriend(uuid) && BurrowClient.connection() != null) {
            addRenderableWidget(Button.builder(Component.literal("+ Friend"), b -> {
                BurrowClient.connection().addFriend(name);
                b.active = false;
                b.setMessage(Component.literal("Sent"));
            }).bounds(curX, btnY, 60, 18).build());
        }

        int banW = 42;
        int kickW = 44;
        int banX = x + w - banW - 6;
        int kickX = banX - kickW - gap;

        addRenderableWidget(Button.builder(Component.literal("Kick"), b -> {
            HostPermissions.kickPlayer(server, uuid, "Kicked by host.");
            this.expandedPlayer = null;
            rebuildWidgets();
        }).bounds(kickX, btnY, kickW, 18).build());

        addRenderableWidget(Button.builder(Component.literal("Ban").withColor(0xFFFF8A8A), b -> {
            HostPermissions.banPlayer(server, uuid, name);
            this.expandedPlayer = null;
            rebuildWidgets();
        }).bounds(banX, btnY, banW, 18).build());
    }

    private void initBansTab(IntegratedServer server, Map<UUID, String> banned) {
        if (banned.isEmpty()) {
            this.emptyMessage = "No banned players in this world.";
            return;
        }
        int innerX = this.contentX + 8;
        int innerW = this.contentW - 16;
        List<Map.Entry<UUID, String>> entries = new ArrayList<>(banned.entrySet());
        int perPage = 3;
        int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        if (this.listPage >= pages) this.listPage = 0;
        int from = this.listPage * perPage;
        int to = Math.min(entries.size(), from + perPage);

        int y = this.contentY + 8;
        for (Map.Entry<UUID, String> e : entries.subList(from, to)) {
            this.banRows.add(new BanRowDisplay(e.getKey(), e.getValue(), innerX, y, innerW, 28));
            addRenderableWidget(Button.builder(Component.literal("Unban"), b -> {
                HostPermissions.unbanPlayer(server, e.getKey());
                rebuildWidgets();
            }).bounds(innerX + innerW - 60, y + 5, 54, 18).build());
            y += 32;
        }

        if (pages > 1) {
            addPagerControls(innerX, this.contentY + CONTENT_H - 20, innerW, pages);
        }
    }

    private void addPagerControls(int x, int y, int w, int pages) {
        Button prev = Button.builder(Component.literal("< Prev"), b -> {
            this.listPage--;
            rebuildWidgets();
        }).bounds(x, y, 52, 16).build();
        prev.active = this.listPage > 0;
        addRenderableWidget(prev);

        Button next = Button.builder(Component.literal("Next >"), b -> {
            this.listPage++;
            rebuildWidgets();
        }).bounds(x + w - 52, y, 52, 16).build();
        next.active = this.listPage < pages - 1;
        addRenderableWidget(next);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);

        BurrowUi.drawModalPanel(g, this.panelX, this.panelY, PANEL_W, PANEL_H);

        Font font = this.font;
        IntegratedServer server = this.minecraft.getSingleplayerServer();
        boolean hosting = HostSession.isHosting();
        String title = hosting ? "Host Settings" : "Host World";
        String worldName = server != null ? HostSession.safeWorldName(server) : "";

        g.text(font, title, this.contentX, this.panelY + BurrowUi.HEADER_TITLE_Y, BurrowUi.TEXT_PRIMARY, true);
        if (!worldName.isEmpty()) {
            g.text(font, worldName, this.contentX, this.panelY + BurrowUi.HEADER_SUB_Y, BurrowUi.TEXT_SECONDARY, false);
        }

        if (server != null) {
            HostWorldState state = HostSession.worldState(server);
            int count = Math.max(1, server.getPlayerCount());
            int cap = state != null ? state.maxPlayers() : 8;
            if (hosting && state != null && state.locked()) {
                BurrowUi.drawStatusPill(g, font, this.contentX + this.contentW, this.panelY + BurrowUi.HEADER_TITLE_Y,
                        BurrowUi.DOT_LOCKED, "LOCKED · " + count + "/" + cap);
            } else if (hosting) {
                BurrowUi.drawStatusPill(g, font, this.contentX + this.contentW, this.panelY + BurrowUi.HEADER_TITLE_Y,
                        BurrowUi.DOT_LIVE, "LIVE · " + count + "/" + cap);
            } else {
                BurrowUi.drawStatusPill(g, font, this.contentX + this.contentW, this.panelY + BurrowUi.HEADER_TITLE_Y,
                        BurrowUi.DOT_OFFLINE, "OFFLINE");
            }
        }

        BurrowUi.drawInsetBox(g, this.contentX, this.contentY, this.contentW, CONTENT_H);

        for (RowLabel lbl : this.settingLabels) {
            g.text(font, lbl.text(), lbl.x(), lbl.y(), BurrowUi.TEXT_PRIMARY, false);
        }

        if (this.playersSubHeader != null) {
            g.text(font, this.playersSubHeader, this.contentX + 8, this.contentY + 10, BurrowUi.TEXT_SECONDARY, false);
        }

        for (BanRowDisplay row : this.banRows) {
            g.fill(row.x(), row.y(), row.x() + row.w(), row.y() + row.h(), BurrowUi.CARD_BG);
            g.outline(row.x(), row.y(), row.w(), row.h(), BurrowUi.CARD_BORDER);
            PlayerHeads.draw(g, row.uuid(), row.name(), row.x() + 5, row.y() + 4, 20);
            g.text(font, row.name(), row.x() + 31, row.y() + 10, BurrowUi.TEXT_PRIMARY, true);
        }

        if (this.emptyMessage != null) {
            g.centeredText(font, this.emptyMessage,
                    this.contentX + this.contentW / 2,
                    this.contentY + CONTENT_H / 2 - 4,
                    BurrowUi.TEXT_MUTED);
        }
    }

    private void switchTab(Tab next) {
        this.tab = next;
        this.listPage = 0;
        this.expandedPlayer = null;
        rebuildWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        if (++this.tickCounter % 20 == 0 && this.minecraft != null) {
            IntegratedServer server = this.minecraft.getSingleplayerServer();
            if (server != null) {
                int count = Math.max(1, server.getPlayerCount());
                if (count != this.lastSeenPlayerCount) {
                    rebuildWidgets();
                }
            }
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

    private static int roleColor(HostWorldState.Role role) {
        return switch (role) {
            case OPERATOR -> BurrowUi.ROLE_OP;
            case MEMBER -> BurrowUi.ROLE_MEMBER;
            case VISITOR -> BurrowUi.ROLE_VISITOR;
        };
    }

    private static String accessTooltip(HostWorldState.AccessMode m) {
        return switch (m) {
            case INVITE_ONLY -> "Only players you explicitly invite can see or join.";
            case FRIENDS -> "Your Burrow friends can join directly.";
            case FRIENDS_OF_FRIENDS -> "Your friends and their direct friends (1 hop) can join.";
            case OPEN -> "Anyone connected along your friend chain (friends of friends of friends...).";
        };
    }

    private static String roleTooltip(HostWorldState.Role r) {
        return switch (r) {
            case VISITOR -> "Can walk around and use doors/buttons, but cannot break blocks, open chests, or pick up items.";
            case MEMBER -> "Standard survival/world gameplay without operator commands.";
            case OPERATOR -> "Full operator permissions and commands.";
        };
    }

    /** Vanilla slider for Player Cap (2..32). */
    private static final class PlayerCapSlider extends AbstractSliderButton {
        private static final int MIN = 2;
        private static final int MAX = 32;
        private final HostWorldState state;

        PlayerCapSlider(int x, int y, int w, int h, HostWorldState state) {
            super(x, y, w, h, Component.empty(), (double) (state.maxPlayers() - MIN) / (MAX - MIN));
            this.state = state;
            updateMessage();
        }

        private int capValue() {
            return MIN + (int) Math.round(this.value * (MAX - MIN));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(capValue() + " players"));
        }

        @Override
        protected void applyValue() {
            int cap = capValue();
            if (cap != this.state.maxPlayers()) {
                this.state.setMaxPlayers(cap);
                HostSession.syncState();
            }
        }
    }

    /** Sleek dark card for a connected player (head, name, crisp scaled role pill, join source). */
    private static final class PlayerCardWidget extends AbstractWidget {
        private final UUID uuid;
        private final String name;
        private final String roleLabel;
        private final int roleColor;
        private final String subtitle;
        private final boolean interactive;
        private final boolean expanded;
        private final Runnable onToggle;

        PlayerCardWidget(
                int x, int y, int w, int h,
                UUID uuid, String name, String roleLabel, int roleColor,
                String subtitle, boolean interactive, boolean expanded, Runnable onToggle
        ) {
            super(x, y, w, h, Component.literal(name));
            this.uuid = uuid;
            this.name = name;
            this.roleLabel = roleLabel;
            this.roleColor = roleColor;
            this.subtitle = subtitle;
            this.interactive = interactive;
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
                    : (this.interactive && this.isHovered ? BurrowUi.CARD_HOVER : BurrowUi.CARD_BG);
            g.fill(x, y, x + w, y + h, bg);
            g.outline(x, y, w, h, this.expanded ? BurrowUi.CARD_BORDER_ACTIVE : BurrowUi.CARD_BORDER);

            if (this.expanded) {
                g.fill(x, y + h, x + w, y + h + 24, BurrowUi.DRAWER_BG);
                g.outline(x, y + h - 1, w, 25, BurrowUi.CARD_BORDER_ACTIVE);
            }

            PlayerHeads.draw(g, this.uuid, this.name, x + 5, y + 5, 20);

            Font font = Minecraft.getInstance().font;
            int textX = x + 31;
            g.text(font, this.name, textX, y + 5, BurrowUi.TEXT_PRIMARY, true);
            int nameW = font.width(this.name);
            BurrowUi.drawTagPill(g, font, textX + nameW + 5, y + 4, this.roleLabel, this.roleColor);
            g.text(font, this.subtitle, textX, y + 17, BurrowUi.TEXT_SECONDARY, false);

            if (this.interactive) {
                String chevron = this.expanded ? "v" : ">";
                int chevColor = this.isHovered || this.expanded ? BurrowUi.TEXT_PRIMARY : BurrowUi.TEXT_MUTED;
                g.text(font, chevron, x + w - 12, y + 11, chevColor, false);
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (this.interactive) {
                playButtonClickSound(Minecraft.getInstance().getSoundManager());
                this.onToggle.run();
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** Same-size vanilla-styled sub-panel to invite an online friend into the hosted world. */
    private static class InviteFriendScreen extends Screen {
        private final Screen parent;
        private int modalX;
        private int modalY;
        private int innerX;
        private int innerW;
        private final List<BanRowDisplay> rows = new ArrayList<>();
        private @Nullable String emptyMsg;

        InviteFriendScreen(Screen parent) {
            super(Component.literal("Invite Friend"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            this.rows.clear();
            this.emptyMsg = null;
            this.modalX = (this.width - BurrowUi.PANEL_W) / 2;
            this.modalY = Math.max(2, (this.height - BurrowUi.PANEL_H) / 2);
            this.innerX = this.modalX + BurrowUi.PAD;
            this.innerW = BurrowUi.PANEL_W - BurrowUi.PAD * 2;

            List<FriendsState.Friend> online = new ArrayList<>();
            for (FriendsState.Friend f : BurrowClient.friends().friends()) {
                if (f.isOnline()) {
                    online.add(f);
                }
            }

            int boxX = this.innerX + 8;
            int boxW = this.innerW - 16;
            int y = this.modalY + BurrowUi.TABS_Y + 8;

            if (online.isEmpty()) {
                this.emptyMsg = "No friends online right now.";
            } else {
                for (FriendsState.Friend f : online.subList(0, Math.min(online.size(), 4))) {
                    this.rows.add(new BanRowDisplay(f.uuid(), f.name(), boxX, y, boxW, 28));
                    addRenderableWidget(Button.builder(Component.literal("Invite"), b -> {
                        HostSession.inviteFriend(f.uuid());
                        b.active = false;
                        b.setMessage(Component.literal("Invited"));
                    }).bounds(boxX + boxW - 60, y + 5, 54, 18).build());
                    y += 32;
                }
            }

            addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                    .bounds(this.innerX + this.innerW - 84, this.modalY + BurrowUi.FOOTER_Y, 84, 20).build());
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            super.extractBackground(g, mouseX, mouseY, partialTick);
            BurrowUi.drawModalPanel(g, this.modalX, this.modalY, BurrowUi.PANEL_W, BurrowUi.PANEL_H);
            g.text(this.font, "Invite Friend to World", this.innerX, this.modalY + BurrowUi.HEADER_TITLE_Y, BurrowUi.TEXT_PRIMARY, true);
            g.text(this.font, "Invites expire after 30 seconds", this.innerX, this.modalY + BurrowUi.HEADER_SUB_Y, BurrowUi.TEXT_SECONDARY, false);
            BurrowUi.drawInsetBox(g, this.innerX, this.modalY + BurrowUi.TABS_Y, this.innerW, 155);

            for (BanRowDisplay row : this.rows) {
                g.fill(row.x(), row.y(), row.x() + row.w(), row.y() + row.h(), BurrowUi.CARD_BG);
                g.outline(row.x(), row.y(), row.w(), row.h(), BurrowUi.CARD_BORDER);
                PlayerHeads.draw(g, row.uuid(), row.name(), row.x() + 5, row.y() + 4, 20);
                g.text(this.font, row.name(), row.x() + 31, row.y() + 10, BurrowUi.TEXT_PRIMARY, true);
            }
            if (this.emptyMsg != null) {
                g.centeredText(this.font, this.emptyMsg,
                        this.modalX + BurrowUi.PANEL_W / 2, this.modalY + BurrowUi.TABS_Y + 74, BurrowUi.TEXT_MUTED);
            }
        }

        @Override
        public void onClose() {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(this.parent);
            }
        }
    }
}
