package dev.meeyah.burrow.ui;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.config.BurrowConfig;
import dev.meeyah.burrow.session.HostPermissions;
import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.session.HostWorldState;
import dev.meeyah.burrow.state.FriendsState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Vanilla-styled "Host World" / "Host Settings" control panel with smooth
 * scrolling across Settings (including privacy-first 6-char Join Codes),
 * Players (with favorite stars), Bans, and Invite Friend.
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
    private final Runnable hostListener = this::onHostStateChanged;
    private Tab tab = Tab.SETTINGS;
    private @Nullable UUID expandedPlayer = null;
    private double scrollSettings = 0.0;
    private double scrollPlayers = 0.0;
    private double scrollBans = 0.0;
    private int tickCounter = 0;
    private int lastSeenPlayerCount = -1;
    private boolean codeRevealed = false;
    private @Nullable String lastSeenJoinCode = null;
    private long copiedFlashUntilMs = 0L;
    private String copiedFlashText = "Copied!";

    private int panelX;
    private int panelY;
    private int contentX;
    private int contentY;
    private int contentW;
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
        this.playersSubHeader = null;
        this.emptyMessage = null;

        String currentCode = HostSession.joinCode();
        if (!Objects.equals(currentCode, this.lastSeenJoinCode)) {
            this.lastSeenJoinCode = currentCode;
            this.codeRevealed = false;
        }

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
        int viewX = this.contentX + 2;
        int viewY = this.contentY + 2;
        int viewW = this.contentW - 4;
        int viewH = CONTENT_H - 4;

        BurrowUi.ScrollContainer scroller = new BurrowUi.ScrollContainer(
                viewX, viewY, viewW, viewH, this.scrollSettings, v -> this.scrollSettings = v);
        List<RowLabel> labels = new ArrayList<>();

        int labelX = this.contentX + 9;
        int ctrlW = 152;
        int ctrlX = this.contentX + this.contentW - ctrlW - 9;
        int rowY = viewY + 4;
        int rowStep = 21;
        int btnH = 18;

        labels.add(new RowLabel("Who Can Join", labelX, rowY + 5));
        scroller.addChild(CycleButton.<HostWorldState.AccessMode>builder(
                        m -> Component.literal(m.label()), state.access())
                .withValues(HostWorldState.AccessMode.values())
                .displayOnlyValue()
                .withTooltip(m -> Tooltip.create(Component.literal(accessTooltip(m))))
                .create(ctrlX, rowY, ctrlW, btnH, Component.literal("Who Can Join"), (btn, val) -> {
                    state.setAccess(val);
                    HostSession.syncState();
                }));
        rowY += rowStep;

        labels.add(new RowLabel("Join Code", labelX, rowY + 5));
        String code = HostSession.joinCode();
        if (code == null || !hosting) {
            Button genBtn = Button.builder(Component.literal("Generate Code"), b -> HostSession.generateJoinCode())
                    .bounds(ctrlX, rowY, ctrlW, btnH).build();
            genBtn.active = hosting;
            genBtn.setTooltip(Tooltip.create(Component.literal(
                    hosting
                            ? "Generate a 6-character code so anyone (including vanilla players via <CODE>.burrow.meeyah.dev) can join."
                            : "Start hosting first to generate a join code.")));
            scroller.addChild(genBtn);
        } else {
            int codeW = 82;
            int regenW = 32;
            int offW = 30;
            int gap = 4;
            scroller.addChild(new JoinCodeButton(
                    ctrlX, rowY, codeW, btnH,
                    code,
                    () -> this.codeRevealed,
                    () -> System.currentTimeMillis() < this.copiedFlashUntilMs ? this.copiedFlashText : null,
                    (shiftHeld) -> {
                        if (this.minecraft != null) {
                            String toCopy = shiftHeld ? HostSession.joinCodeAddress(code) : code;
                            this.minecraft.keyboardHandler.setClipboard(toCopy);
                            this.copiedFlashText = shiftHeld ? "Copied IP!" : "Copied!";
                            this.copiedFlashUntilMs = System.currentTimeMillis() + 1_600L;
                        }
                    },
                    () -> this.codeRevealed = !this.codeRevealed));

            Button regenBtn = Button.builder(Component.literal("New"), b -> {
                this.codeRevealed = false;
                HostSession.regenerateJoinCode();
            }).bounds(ctrlX + codeW + gap, rowY, regenW, btnH).build();
            regenBtn.setTooltip(Tooltip.create(Component.literal("Regenerate a fresh 6-character code.")));
            scroller.addChild(regenBtn);

            Button offBtn = Button.builder(Component.literal("Off").withColor(0xFFFF8A8A), b -> {
                this.codeRevealed = false;
                HostSession.disableJoinCode();
            }).bounds(ctrlX + codeW + gap + regenW + gap, rowY, offW, btnH).build();
            offBtn.setTooltip(Tooltip.create(Component.literal("Disable the current join code.")));
            scroller.addChild(offBtn);
        }
        rowY += rowStep;

        labels.add(new RowLabel("Default Role", labelX, rowY + 5));
        scroller.addChild(CycleButton.<HostWorldState.Role>builder(
                        r -> Component.literal(r.label()), state.defaultRole())
                .withValues(HostWorldState.Role.values())
                .displayOnlyValue()
                .withTooltip(r -> Tooltip.create(Component.literal(roleTooltip(r))))
                .create(ctrlX, rowY, ctrlW, btnH, Component.literal("Default Role"), (btn, val) -> {
                    state.setDefaultRole(val);
                }));
        rowY += rowStep;

        labels.add(new RowLabel("Who Can Invite", labelX, rowY + 5));
        scroller.addChild(CycleButton.<HostWorldState.InvitePerm>builder(
                        p -> Component.literal(p.label()), state.invitePerm())
                .withValues(HostWorldState.InvitePerm.values())
                .displayOnlyValue()
                .withTooltip(p -> Tooltip.create(Component.literal("Controls who can send world invites.")))
                .create(ctrlX, rowY, ctrlW, btnH, Component.literal("Who Can Invite"), (btn, val) -> {
                    state.setInvitePerm(val);
                    HostSession.syncState();
                }));
        rowY += rowStep;

        labels.add(new RowLabel("Player Cap", labelX, rowY + 5));
        scroller.addChild(new PlayerCapSlider(ctrlX, rowY, ctrlW, btnH, state));
        rowY += rowStep;

        labels.add(new RowLabel("Mismatched Mods", labelX, rowY + 5));
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
        scroller.addChild(mismatchBtn);
        rowY += rowStep;

        labels.add(new RowLabel("Join Lock", labelX, rowY + 5));
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
        scroller.addChild(lockBtn);
        rowY += rowStep;

        scroller.setBackgroundRenderer((g, mx, my, scrollPx) -> {
            for (RowLabel lbl : labels) {
                g.drawString(this.font, lbl.text(), lbl.x(), lbl.y() - scrollPx, BurrowUi.TEXT_PRIMARY, false);
            }
        });
        scroller.setContentHeight(rowY - viewY + 2);
        addRenderableWidget(scroller);
    }

    private void initPlayersTab(
            IntegratedServer server,
            HostWorldState state,
            List<ServerPlayer> players,
            boolean hosting
    ) {
        BurrowConfig cfg = BurrowClient.config();
        FriendsState fs = BurrowClient.friends();

        List<ServerPlayer> ordered = new ArrayList<>(players);
        ordered.sort((a, b) -> {
            boolean aHost = server.isSingleplayerOwner(a.nameAndId());
            boolean bHost = server.isSingleplayerOwner(b.nameAndId());
            if (aHost != bHost) return aHost ? -1 : 1;
            boolean aFav = cfg != null && fs.isFriend(a.getUUID()) && cfg.isFavorite(a.getUUID());
            boolean bFav = cfg != null && fs.isFriend(b.getUUID()) && cfg.isFavorite(b.getUUID());
            if (aFav != bFav) return aFav ? -1 : 1;
            return a.getScoreboardName().compareToIgnoreCase(b.getScoreboardName());
        });

        if (this.expandedPlayer != null) {
            boolean stillHere = ordered.stream().anyMatch(p -> this.expandedPlayer.equals(p.getUUID()));
            if (!stillHere) {
                this.expandedPlayer = null;
            }
        }

        int innerX = this.contentX + 6;
        int innerW = this.contentW - 15;
        int count = Math.max(1, ordered.size());
        this.playersSubHeader = count + " of " + state.maxPlayers() + " players in world";

        if (hosting) {
            addRenderableWidget(Button.builder(Component.literal("+ Invite"), b -> {
                if (this.minecraft != null) {
                    this.minecraft.setScreen(new InviteFriendScreen(this));
                }
            }).bounds(this.contentX + this.contentW - 72, this.contentY + 6, 64, 16).build());
        }

        int viewX = this.contentX + 2;
        int viewY = this.contentY + 24;
        int viewW = this.contentW - 4;
        int viewH = CONTENT_H - 26;

        BurrowUi.ScrollContainer scroller = new BurrowUi.ScrollContainer(
                viewX, viewY, viewW, viewH, this.scrollPlayers, v -> this.scrollPlayers = v);

        int y = viewY + 2;
        for (ServerPlayer p : ordered) {
            UUID uuid = p.getUUID();
            String name = p.getScoreboardName();
            boolean isHost = server.isSingleplayerOwner(p.nameAndId());
            boolean expanded = !isHost && uuid.equals(this.expandedPlayer);

            String roleLabel = isHost ? "Host" : state.roleOf(uuid).label();
            int roleColor = isHost ? BurrowUi.ROLE_HOST : roleColor(state.roleOf(uuid));
            String subtitle = isHost ? "World owner" : state.joinInfoOf(uuid).describe();

            scroller.addChild(new PlayerCardWidget(
                    innerX, y, innerW, 30,
                    uuid, name, roleLabel, roleColor, subtitle, !isHost, expanded,
                    () -> {
                        this.expandedPlayer = expanded ? null : uuid;
                        rebuildWidgets();
                    }));

            if (!isHost && cfg != null && fs.isFriend(uuid)) {
                scroller.addChild(new BurrowUi.StarButton(
                        innerX + innerW - 29, y + 8, cfg.isFavorite(uuid),
                        () -> {
                            cfg.toggleFavorite(uuid);
                            fs.notifyChanged();
                            rebuildWidgets();
                        }));
            }
            y += 30;

            if (expanded) {
                addExpandedPlayerDrawer(scroller, server, state, uuid, name, innerX, y, innerW);
                y += 25;
            }
            y += 4;
        }

        scroller.setContentHeight(y - viewY + 2);
        addRenderableWidget(scroller);
    }

    private void addExpandedPlayerDrawer(
            BurrowUi.ScrollContainer scroller,
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
        scroller.addChild(roleBtn);
        curX += 96 + gap;

        if (!BurrowClient.friends().isFriend(uuid) && BurrowClient.connection() != null) {
            scroller.addChild(Button.builder(Component.literal("+ Friend"), b -> {
                BurrowClient.connection().addFriend(name);
                b.active = false;
                b.setMessage(Component.literal("Sent"));
            }).bounds(curX, btnY, 60, 18).build());
        }

        int banW = 42;
        int kickW = 44;
        int banX = x + w - banW - 6;
        int kickX = banX - kickW - gap;

        scroller.addChild(Button.builder(Component.literal("Kick"), b -> {
            HostPermissions.kickPlayer(server, uuid, "Kicked by host.");
            this.expandedPlayer = null;
            rebuildWidgets();
        }).bounds(kickX, btnY, kickW, 18).build());

        scroller.addChild(Button.builder(Component.literal("Ban").withColor(0xFFFF8A8A), b -> {
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
        int viewX = this.contentX + 2;
        int viewY = this.contentY + 2;
        int viewW = this.contentW - 4;
        int viewH = CONTENT_H - 4;

        BurrowUi.ScrollContainer scroller = new BurrowUi.ScrollContainer(
                viewX, viewY, viewW, viewH, this.scrollBans, v -> this.scrollBans = v);
        List<BanRowDisplay> rows = new ArrayList<>();

        int innerX = this.contentX + 6;
        int innerW = this.contentW - 15;
        int y = viewY + 4;
        for (Map.Entry<UUID, String> e : banned.entrySet()) {
            rows.add(new BanRowDisplay(e.getKey(), e.getValue(), innerX, y, innerW, 28));
            scroller.addChild(Button.builder(Component.literal("Unban"), b -> {
                HostPermissions.unbanPlayer(server, e.getKey());
                rebuildWidgets();
            }).bounds(innerX + innerW - 60, y + 5, 54, 18).build());
            y += 32;
        }

        scroller.setBackgroundRenderer((g, mx, my, scrollPx) -> {
            for (BanRowDisplay row : rows) {
                int ry = row.y() - scrollPx;
                g.fill(row.x(), ry, row.x() + row.w(), ry + row.h(), BurrowUi.CARD_BG);
                g.renderOutline(row.x(), ry, row.w(), row.h(), BurrowUi.CARD_BORDER);
                PlayerHeads.draw(g, row.uuid(), row.name(), row.x() + 5, ry + 4, 20);
                g.drawString(this.font, row.name(), row.x() + 31, ry + 10, BurrowUi.TEXT_PRIMARY, true);
            }
        });
        scroller.setContentHeight(y - viewY + 2);
        addRenderableWidget(scroller);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);

        BurrowUi.drawModalPanel(g, this.panelX, this.panelY, PANEL_W, PANEL_H);

        Font font = this.font;
        IntegratedServer server = this.minecraft.getSingleplayerServer();
        boolean hosting = HostSession.isHosting();
        String title = hosting ? "Host Settings" : "Host World";
        String worldName = server != null ? HostSession.safeWorldName(server) : "";

        g.drawString(font, title, this.contentX, this.panelY + BurrowUi.HEADER_TITLE_Y, BurrowUi.TEXT_PRIMARY, true);
        if (!worldName.isEmpty()) {
            g.drawString(font, worldName, this.contentX, this.panelY + BurrowUi.HEADER_SUB_Y, BurrowUi.TEXT_SECONDARY, false);
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

        if (this.playersSubHeader != null) {
            g.drawString(font, this.playersSubHeader, this.contentX + 8, this.contentY + 10, BurrowUi.TEXT_SECONDARY, false);
        }

        if (this.emptyMessage != null) {
            g.drawCenteredString(font, this.emptyMessage,
                    this.contentX + this.contentW / 2,
                    this.contentY + CONTENT_H / 2 - 4,
                    BurrowUi.TEXT_MUTED);
        }
    }

    private void switchTab(Tab next) {
        this.tab = next;
        this.expandedPlayer = null;
        rebuildWidgets();
    }

    private void onHostStateChanged() {
        if (this.minecraft != null && this.minecraft.gui != null && this.minecraft.screen == this) {
            rebuildWidgets();
        }
    }

    @Override
    public void added() {
        HostSession.addListener(this.hostListener);
    }

    @Override
    public void removed() {
        HostSession.removeListener(this.hostListener);
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
            this.minecraft.setScreen(this.parent);
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
            case INVITE_ONLY -> "Only players you explicitly invite (or who use a Join Code) can join.";
            case FRIENDS -> "Your Burrow friends can join directly.";
            case FRIENDS_OF_FRIENDS -> "Your friends, plus their friends while a mutual friend is in the world.";
        };
    }

    private static String roleTooltip(HostWorldState.Role r) {
        return switch (r) {
            case VISITOR -> "Can walk around and use doors/buttons, but cannot break blocks, open chests, or pick up items.";
            case MEMBER -> "Standard survival/world gameplay without operator commands.";
            case OPERATOR -> "Full operator permissions and commands.";
        };
    }

    /**
     * Privacy-first Join Code button: hidden by default ("Code Hidden"),
     * left-click copies the 6-char code (Shift+left-click copies vanilla IP),
     * right-click toggles reveal/hide.
     */
    private static final class JoinCodeButton extends AbstractWidget {
        private final String code;
        private final java.util.function.BooleanSupplier isRevealed;
        private final java.util.function.Supplier<@Nullable String> flashText;
        private final java.util.function.Consumer<Boolean> onCopy;
        private final Runnable onToggleReveal;

        JoinCodeButton(
                int x, int y, int w, int h,
                String code,
                java.util.function.BooleanSupplier isRevealed,
                java.util.function.Supplier<@Nullable String> flashText,
                java.util.function.Consumer<Boolean> onCopy,
                Runnable onToggleReveal
        ) {
            super(x, y, w, h, Component.literal("Code Hidden"));
            this.code = code;
            this.isRevealed = isRevealed;
            this.flashText = flashText;
            this.onCopy = onCopy;
            this.onToggleReveal = onToggleReveal;
            setTooltip(Tooltip.create(Component.literal(
                    "Left-click: copy code · Shift-click: copy vanilla IP\nRight-click: show / hide code")));
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            BurrowVanillaSprites.SpriteSpec sprite = this.isHoveredOrFocused()
                    ? BurrowVanillaSprites.BUTTON_HOVER
                    : BurrowVanillaSprites.BUTTON_IDLE;
            BurrowVanillaSprites.draw(g, sprite, getX(), getY(), getWidth(), getHeight());

            Font font = Minecraft.getInstance().font;
            String flash = this.flashText.get();
            String text;
            int color;
            if (flash != null) {
                text = flash;
                color = 0xFF7AE89B;
            } else if (this.isRevealed.getAsBoolean()) {
                text = this.code;
                color = 0xFFFFD866;
            } else {
                text = "Code Hidden";
                color = BurrowUi.TEXT_SECONDARY;
            }
            g.drawCenteredString(font, text, getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, color);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (!this.active || !this.visible || !isMouseOver(event.x(), event.y())) {
                return false;
            }
            if (event.button() == 0) {
                playButtonClickSound(Minecraft.getInstance().getSoundManager());
                boolean shift = (event.modifiers() & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0;
                this.onCopy.accept(shift);
                return true;
            }
            if (event.button() == 1) {
                playButtonClickSound(Minecraft.getInstance().getSoundManager());
                this.onToggleReveal.run();
                return true;
            }
            return false;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
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
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();

            int bg = this.expanded
                    ? BurrowUi.CARD_SELECTED
                    : (this.interactive && this.isHovered ? BurrowUi.CARD_HOVER : BurrowUi.CARD_BG);
            g.fill(x, y, x + w, y + h, bg);
            g.renderOutline(x, y, w, h, this.expanded ? BurrowUi.CARD_BORDER_ACTIVE : BurrowUi.CARD_BORDER);

            if (this.expanded) {
                g.fill(x, y + h, x + w, y + h + 24, BurrowUi.DRAWER_BG);
                g.renderOutline(x, y + h - 1, w, 25, BurrowUi.CARD_BORDER_ACTIVE);
            }

            PlayerHeads.draw(g, this.uuid, this.name, x + 5, y + 5, 20);

            Font font = Minecraft.getInstance().font;
            int textX = x + 31;
            g.drawString(font, this.name, textX, y + 5, BurrowUi.TEXT_PRIMARY, true);
            int nameW = font.width(this.name);
            BurrowUi.drawTagPill(g, font, textX + nameW + 5, y + 4, this.roleLabel, this.roleColor);
            g.drawString(font, BurrowFriendsScreen.ellipsize(font, this.subtitle, w - 66),
                    textX, y + 17, BurrowUi.TEXT_SECONDARY, false);

            if (this.interactive) {
                String chevron = this.expanded ? "v" : ">";
                int chevColor = this.isHovered || this.expanded ? BurrowUi.TEXT_PRIMARY : BurrowUi.TEXT_MUTED;
                g.drawString(font, chevron, x + w - 12, y + 11, chevColor, false);
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

    /** Same-size vanilla-styled sub-panel to invite an online friend into the hosted world (smooth-scrolled). */
    private static class InviteFriendScreen extends Screen {
        private final Screen parent;
        private int modalX;
        private int modalY;
        private int innerX;
        private int innerW;
        private double scrollY = 0.0;
        private @Nullable String emptyMsg;

        InviteFriendScreen(Screen parent) {
            super(Component.literal("Invite Friend"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            this.emptyMsg = null;
            this.modalX = (this.width - BurrowUi.PANEL_W) / 2;
            this.modalY = Math.max(2, (this.height - BurrowUi.PANEL_H) / 2);
            this.innerX = this.modalX + BurrowUi.PAD;
            this.innerW = BurrowUi.PANEL_W - BurrowUi.PAD * 2;

            BurrowConfig cfg = BurrowClient.config();
            List<FriendsState.Friend> online = new ArrayList<>();
            for (FriendsState.Friend f : BurrowClient.friends().friends()) {
                if (f.isOnline()) {
                    online.add(f);
                }
            }
            online.sort(Comparator
                    .comparingInt((FriendsState.Friend f) -> (cfg != null && cfg.isFavorite(f.uuid())) ? 0 : 1)
                    .thenComparing(f -> f.name() == null ? "" : f.name().toLowerCase()));

            if (online.isEmpty()) {
                this.emptyMsg = "No friends online right now.";
            } else {
                int viewX = this.innerX + 2;
                int viewY = this.modalY + BurrowUi.TABS_Y + 2;
                int viewW = this.innerW - 4;
                int viewH = 155 - 4;

                BurrowUi.ScrollContainer scroller = new BurrowUi.ScrollContainer(
                        viewX, viewY, viewW, viewH, this.scrollY, v -> this.scrollY = v);
                List<BanRowDisplay> rows = new ArrayList<>();

                int boxX = this.innerX + 6;
                int boxW = this.innerW - 15;
                int y = viewY + 4;
                for (FriendsState.Friend f : online) {
                    rows.add(new BanRowDisplay(f.uuid(), f.name(), boxX, y, boxW, 28));
                    int btnX = boxX + boxW - 60;
                    if (cfg != null && f.uuid() != null) {
                        scroller.addChild(new BurrowUi.StarButton(
                                btnX - 17, y + 7, cfg.isFavorite(f.uuid()),
                                () -> {
                                    cfg.toggleFavorite(f.uuid());
                                    BurrowClient.friends().notifyChanged();
                                    rebuildWidgets();
                                }));
                    }
                    scroller.addChild(Button.builder(Component.literal("Invite"), b -> {
                        HostSession.inviteFriend(f.uuid());
                        b.active = false;
                        b.setMessage(Component.literal("Invited"));
                    }).bounds(btnX, y + 5, 54, 18).build());
                    y += 32;
                }

                scroller.setBackgroundRenderer((g, mx, my, scrollPx) -> {
                    for (BanRowDisplay row : rows) {
                        int ry = row.y() - scrollPx;
                        g.fill(row.x(), ry, row.x() + row.w(), ry + row.h(), BurrowUi.CARD_BG);
                        g.renderOutline(row.x(), ry, row.w(), row.h(), BurrowUi.CARD_BORDER);
                        PlayerHeads.draw(g, row.uuid(), row.name(), row.x() + 5, ry + 4, 20);
                        g.drawString(this.font, row.name(), row.x() + 31, ry + 10, BurrowUi.TEXT_PRIMARY, true);
                    }
                });
                scroller.setContentHeight(y - viewY + 2);
                addRenderableWidget(scroller);
            }

            addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                    .bounds(this.innerX + this.innerW - 84, this.modalY + BurrowUi.FOOTER_Y, 84, 20).build());
        }

        @Override
        public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.renderBackground(g, mouseX, mouseY, partialTick);
            BurrowUi.drawModalPanel(g, this.modalX, this.modalY, BurrowUi.PANEL_W, BurrowUi.PANEL_H);
            g.drawString(this.font, "Invite Friend to World", this.innerX, this.modalY + BurrowUi.HEADER_TITLE_Y, BurrowUi.TEXT_PRIMARY, true);
            g.drawString(this.font, "Invites expire after 30 seconds", this.innerX, this.modalY + BurrowUi.HEADER_SUB_Y, BurrowUi.TEXT_SECONDARY, false);
            BurrowUi.drawInsetBox(g, this.innerX, this.modalY + BurrowUi.TABS_Y, this.innerW, 155);

            if (this.emptyMsg != null) {
                g.drawCenteredString(this.font, this.emptyMsg,
                        this.modalX + BurrowUi.PANEL_W / 2, this.modalY + BurrowUi.TABS_Y + 74, BurrowUi.TEXT_MUTED);
            }
        }

        @Override
        public void onClose() {
            if (this.minecraft != null) {
                this.minecraft.setScreen(this.parent);
            }
        }
    }
}
