package dev.meeyah.burrow.ui;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.session.HostPermissions;
import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.session.HostWorldState;
import dev.meeyah.burrow.state.FriendsState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Burrow's "Host World" control panel, replacing vanilla's Open to LAN /
 * Multiplayer Options screen.
 *
 * Lets the host start/stop sharing, lock/unlock joins, configure access mode
 * (Invite Only / Friends / Friends of Friends / Open), player cap, default
 * role, invite permissions, manage connected players (join source, role,
 * friend request, kick, ban), invite online friends, and manage bans.
 */
public class HostWorldScreen extends Screen {
    private static final int PANEL_WIDTH = 244;
    private static final int ROW_SPACING = 4;
    private static final int MAX_LIST_ROWS = 6;

    private enum Tab { SETTINGS, PLAYERS, BANS }

    private final @Nullable Screen parent;
    private Tab tab = Tab.SETTINGS;
    private int listPage = 0;
    private int tickCounter = 0;
    private int lastSeenPlayerCount = -1;

    public HostWorldScreen(@Nullable Screen parent) {
        super(Component.literal("Host World"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Minecraft mc = this.minecraft;
        Font font = mc.font;
        IntegratedServer server = mc.getSingleplayerServer();

        LinearLayout layout = LinearLayout.vertical().spacing(ROW_SPACING);
        layout.defaultCellSetting().alignHorizontallyCenter();

        if (server == null) {
            layout.addChild(new StringWidget(Component.literal("Host World"), font));
            layout.addChild(new StringWidget(PANEL_WIDTH, 9,
                    Component.literal("Only available in a singleplayer world."), font));
            layout.addChild(Button.builder(Component.literal("Done"), b -> onClose())
                    .width(PANEL_WIDTH).build());
            finishLayout(layout);
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

        // ----- Header -----
        String worldName = HostSession.safeWorldName(server);
        layout.addChild(new StringWidget(Component.literal("Host World — " + worldName), font));
        layout.addChild(new StringWidget(PANEL_WIDTH, 9, statusLine(hosting, state, playerCount), font));

        // ----- Primary action bar: Start/Stop + Lock/Unlock -----
        LinearLayout topActions = LinearLayout.horizontal().spacing(ROW_SPACING);
        int halfWidth = (PANEL_WIDTH - ROW_SPACING) / 2;
        topActions.addChild(Button.builder(
                        Component.literal(hosting ? "Stop Hosting" : "Start Hosting"),
                        b -> {
                            if (HostSession.isHosting()) {
                                HostSession.stop();
                            } else {
                                HostSession.start();
                            }
                            rebuildWidgets();
                        })
                .width(halfWidth).build());
        Button lockBtn = Button.builder(
                        Component.literal(state.locked() ? "Unlock Joins" : "Lock Joins"),
                        b -> {
                            state.setLocked(!state.locked());
                            HostSession.syncState();
                            rebuildWidgets();
                        })
                .width(halfWidth).build();
        lockBtn.active = hosting;
        topActions.addChild(lockBtn);
        layout.addChild(topActions);

        // ----- Tabs -----
        LinearLayout tabRow = LinearLayout.horizontal().spacing(ROW_SPACING);
        int tabWidth = (PANEL_WIDTH - ROW_SPACING * 2) / 3;
        Button settingsTab = Button.builder(Component.literal("Settings"), b -> switchTab(Tab.SETTINGS))
                .width(tabWidth).build();
        settingsTab.active = (this.tab != Tab.SETTINGS);
        Button playersTab = Button.builder(
                        Component.literal("Players (" + playerCount + "/" + state.maxPlayers() + ")"),
                        b -> switchTab(Tab.PLAYERS))
                .width(tabWidth).build();
        playersTab.active = (this.tab != Tab.PLAYERS);
        Button bansTab = Button.builder(
                        Component.literal("Bans (" + banned.size() + ")"),
                        b -> switchTab(Tab.BANS))
                .width(tabWidth).build();
        bansTab.active = (this.tab != Tab.BANS);
        tabRow.addChild(settingsTab);
        tabRow.addChild(playersTab);
        tabRow.addChild(bansTab);
        layout.addChild(tabRow);

        // ----- Active tab body -----
        switch (this.tab) {
            case SETTINGS -> buildSettingsTab(layout, state);
            case PLAYERS -> buildPlayersTab(layout, server, state, players, hosting);
            case BANS -> buildBansTab(layout, server, banned);
        }

        // ----- Footer -----
        layout.addChild(Button.builder(Component.literal("Done"), b -> onClose())
                .width(PANEL_WIDTH).build());

        finishLayout(layout);
    }

    private void buildSettingsTab(LinearLayout layout, HostWorldState state) {
        layout.addChild(CycleButton.<HostWorldState.AccessMode>builder(
                        m -> Component.literal(m.label()), state.access())
                .withValues(HostWorldState.AccessMode.values())
                .create(0, 0, PANEL_WIDTH, 20, Component.literal("Access"), (btn, val) -> {
                    state.setAccess(val);
                    HostSession.syncState();
                    rebuildWidgets();
                }));

        layout.addChild(CycleButton.<HostWorldState.Role>builder(
                        r -> Component.literal(r.label()), state.defaultRole())
                .withValues(HostWorldState.Role.values())
                .create(0, 0, PANEL_WIDTH, 20, Component.literal("Default Role"), (btn, val) -> {
                    state.setDefaultRole(val);
                }));

        layout.addChild(CycleButton.<HostWorldState.InvitePerm>builder(
                        p -> Component.literal(p.label()), state.invitePerm())
                .withValues(HostWorldState.InvitePerm.values())
                .create(0, 0, PANEL_WIDTH, 20, Component.literal("Who Can Invite"), (btn, val) -> {
                    state.setInvitePerm(val);
                    HostSession.syncState();
                }));

        // Player cap: [-] [Player Cap: N] [+]
        LinearLayout capRow = LinearLayout.horizontal().spacing(ROW_SPACING);
        Button minus = Button.builder(Component.literal("-"), b -> {
            state.setMaxPlayers(state.maxPlayers() - 1);
            HostSession.syncState();
            rebuildWidgets();
        }).width(24).build();
        minus.active = state.maxPlayers() > 2;
        Button capLabel = Button.builder(
                Component.literal("Player Cap: " + state.maxPlayers()), b -> {}
        ).width(PANEL_WIDTH - 48 - ROW_SPACING * 2).build();
        capLabel.active = false;
        Button plus = Button.builder(Component.literal("+"), b -> {
            state.setMaxPlayers(state.maxPlayers() + 1);
            HostSession.syncState();
            rebuildWidgets();
        }).width(24).build();
        plus.active = state.maxPlayers() < 32;
        capRow.addChild(minus);
        capRow.addChild(capLabel);
        capRow.addChild(plus);
        layout.addChild(capRow);
    }

    private void buildPlayersTab(
            LinearLayout layout,
            IntegratedServer server,
            HostWorldState state,
            List<ServerPlayer> players,
            boolean hosting
    ) {
        Font font = this.minecraft.font;
        if (hosting) {
            layout.addChild(Button.builder(Component.literal("+ Invite Friend..."), b -> {
                if (this.minecraft != null) {
                    this.minecraft.gui.setScreen(new InviteFriendScreen(this));
                }
            }).width(PANEL_WIDTH).build());
        }

        // Order host first, then guests.
        List<ServerPlayer> ordered = new ArrayList<>(players);
        ordered.sort((a, b) -> {
            boolean aHost = server.isSingleplayerOwner(a.nameAndId());
            boolean bHost = server.isSingleplayerOwner(b.nameAndId());
            if (aHost != bHost) return aHost ? -1 : 1;
            return a.getScoreboardName().compareToIgnoreCase(b.getScoreboardName());
        });

        int pages = Math.max(1, (ordered.size() + MAX_LIST_ROWS - 1) / MAX_LIST_ROWS);
        if (this.listPage >= pages) this.listPage = 0;
        int from = this.listPage * MAX_LIST_ROWS;
        int to = Math.min(ordered.size(), from + MAX_LIST_ROWS);

        if (ordered.isEmpty()) {
            layout.addChild(new StringWidget(PANEL_WIDTH, 9, Component.literal("No players in world."), font));
        } else {
            for (ServerPlayer p : ordered.subList(from, to)) {
                UUID uuid = p.getUUID();
                String name = p.getScoreboardName();
                boolean isHost = server.isSingleplayerOwner(p.nameAndId());
                if (isHost) {
                    Button hostRow = Button.builder(Component.literal(name + " [Host]"), b -> {})
                            .width(PANEL_WIDTH).build();
                    hostRow.active = false;
                    layout.addChild(hostRow);
                } else {
                    HostWorldState.Role role = state.roleOf(uuid);
                    HostWorldState.JoinInfo info = state.joinInfoOf(uuid);
                    String label = name + " [" + role.label() + "] — " + info.shortLabel();
                    layout.addChild(Button.builder(Component.literal(label), b -> {
                        if (this.minecraft != null) {
                            this.minecraft.gui.setScreen(new PlayerManageScreen(this, uuid, name));
                        }
                    }).width(PANEL_WIDTH).build());
                }
            }
            addPager(layout, pages);
        }
    }

    private void buildBansTab(LinearLayout layout, IntegratedServer server, Map<UUID, String> banned) {
        Font font = this.minecraft.font;
        if (banned.isEmpty()) {
            layout.addChild(new StringWidget(PANEL_WIDTH, 9,
                    Component.literal("No banned players in this world."), font));
            return;
        }
        List<Map.Entry<UUID, String>> entries = new ArrayList<>(banned.entrySet());
        int pages = Math.max(1, (entries.size() + MAX_LIST_ROWS - 1) / MAX_LIST_ROWS);
        if (this.listPage >= pages) this.listPage = 0;
        int from = this.listPage * MAX_LIST_ROWS;
        int to = Math.min(entries.size(), from + MAX_LIST_ROWS);

        for (Map.Entry<UUID, String> e : entries.subList(from, to)) {
            LinearLayout row = LinearLayout.horizontal().spacing(ROW_SPACING);
            Button nameBtn = Button.builder(Component.literal(e.getValue()), b -> {})
                    .width(PANEL_WIDTH - 64 - ROW_SPACING).build();
            nameBtn.active = false;
            Button unbanBtn = Button.builder(Component.literal("Unban"), b -> {
                HostPermissions.unbanPlayer(server, e.getKey());
                rebuildWidgets();
            }).width(64).build();
            row.addChild(nameBtn);
            row.addChild(unbanBtn);
            layout.addChild(row);
        }
        addPager(layout, pages);
    }

    private void addPager(LinearLayout layout, int pages) {
        if (pages <= 1) return;
        LinearLayout nav = LinearLayout.horizontal().spacing(ROW_SPACING);
        int half = (PANEL_WIDTH - ROW_SPACING) / 2;
        if (this.listPage > 0) {
            nav.addChild(Button.builder(Component.literal("< Prev"), b -> {
                this.listPage--;
                rebuildWidgets();
            }).width(half).build());
        }
        if (this.listPage < pages - 1) {
            nav.addChild(Button.builder(Component.literal("Next >"), b -> {
                this.listPage++;
                rebuildWidgets();
            }).width(half).build());
        }
        layout.addChild(nav);
    }

    private void switchTab(Tab next) {
        this.tab = next;
        this.listPage = 0;
        rebuildWidgets();
    }

    private Component statusLine(boolean hosting, HostWorldState state, int playerCount) {
        if (!hosting) {
            return Component.literal("Not hosting yet · Ready (" + state.access().label() + ")");
        }
        String lockPart = state.locked() ? " (Locked)" : "";
        return Component.literal(
                "Live" + lockPart + " · " + playerCount + "/" + state.maxPlayers()
                        + " · " + state.access().label());
    }

    private void finishLayout(LinearLayout layout) {
        layout.arrangeElements();
        FrameLayout.alignInRectangle(layout, this.getRectangle(), 0.5F, 0.5F);
        layout.visitWidgets(this::addRenderableWidget);
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

    /** Manage a single connected player: join source, role, friend request, kick, ban. */
    private static class PlayerManageScreen extends Screen {
        private final Screen parent;
        private final UUID playerUuid;
        private final String playerName;

        PlayerManageScreen(Screen parent, UUID playerUuid, String playerName) {
            super(Component.literal("Manage Player"));
            this.parent = parent;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
        }

        @Override
        protected void init() {
            Minecraft mc = this.minecraft;
            Font font = mc.font;
            IntegratedServer server = mc.getSingleplayerServer();
            HostWorldState state = HostSession.worldState(server);
            if (server == null || state == null) {
                onClose();
                return;
            }

            LinearLayout layout = LinearLayout.vertical().spacing(ROW_SPACING);
            layout.defaultCellSetting().alignHorizontallyCenter();

            HostWorldState.JoinInfo info = state.joinInfoOf(this.playerUuid);
            layout.addChild(new StringWidget(Component.literal(this.playerName), font));
            layout.addChild(new StringWidget(PANEL_WIDTH, 9, Component.literal(info.describe()), font));

            layout.addChild(CycleButton.<HostWorldState.Role>builder(
                            r -> Component.literal(r.label()), state.roleOf(this.playerUuid))
                    .withValues(HostWorldState.Role.values())
                    .create(0, 0, PANEL_WIDTH, 20, Component.literal("Role"), (btn, val) -> {
                        HostPermissions.setPlayerRole(server, this.playerUuid, val);
                    }));

            if (!BurrowClient.friends().isFriend(this.playerUuid) && BurrowClient.connection() != null) {
                layout.addChild(Button.builder(Component.literal("Send Friend Request"), b -> {
                    BurrowClient.connection().addFriend(this.playerName);
                    b.active = false;
                    b.setMessage(Component.literal("Friend Request Sent"));
                }).width(PANEL_WIDTH).build());
            }

            layout.addChild(Button.builder(Component.literal("Kick from World"), b -> {
                HostPermissions.kickPlayer(server, this.playerUuid, "Kicked by host.");
                onClose();
            }).width(PANEL_WIDTH).build());

            layout.addChild(Button.builder(Component.literal("Ban from World"), b -> {
                HostPermissions.banPlayer(server, this.playerUuid, this.playerName);
                onClose();
            }).width(PANEL_WIDTH).build());

            layout.addChild(Button.builder(Component.literal("Back"), b -> onClose())
                    .width(PANEL_WIDTH).build());

            layout.arrangeElements();
            FrameLayout.alignInRectangle(layout, this.getRectangle(), 0.5F, 0.5F);
            layout.visitWidgets(this::addRenderableWidget);
        }

        @Override
        public void onClose() {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(this.parent);
            }
        }
    }

    /** Pick an online friend to invite into the currently hosted world. */
    private static class InviteFriendScreen extends Screen {
        private final Screen parent;

        InviteFriendScreen(Screen parent) {
            super(Component.literal("Invite Friend"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            Font font = this.minecraft.font;
            LinearLayout layout = LinearLayout.vertical().spacing(ROW_SPACING);
            layout.defaultCellSetting().alignHorizontallyCenter();

            layout.addChild(new StringWidget(Component.literal("Invite Friend to World"), font));

            List<FriendsState.Friend> online = new ArrayList<>();
            for (FriendsState.Friend f : BurrowClient.friends().friends()) {
                if ("online".equalsIgnoreCase(f.presence())) {
                    online.add(f);
                }
            }
            if (online.isEmpty()) {
                layout.addChild(new StringWidget(PANEL_WIDTH, 9,
                        Component.literal("No friends online right now."), font));
            } else {
                int shown = Math.min(online.size(), MAX_LIST_ROWS);
                for (FriendsState.Friend f : online.subList(0, shown)) {
                    layout.addChild(Button.builder(Component.literal("Invite " + f.name()), b -> {
                        if (BurrowClient.connection() != null) {
                            BurrowClient.connection().sessionInvite(f.uuid(), null);
                        }
                        b.active = false;
                        b.setMessage(Component.literal("Invited " + f.name()));
                    }).width(PANEL_WIDTH).build());
                }
            }

            layout.addChild(Button.builder(Component.literal("Back"), b -> onClose())
                    .width(PANEL_WIDTH).build());

            layout.arrangeElements();
            FrameLayout.alignInRectangle(layout, this.getRectangle(), 0.5F, 0.5F);
            layout.visitWidgets(this::addRenderableWidget);
        }

        @Override
        public void onClose() {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(this.parent);
            }
        }
    }
}
