package dev.meeyah.burrow.ui;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.net.BurrowConnection;
import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.session.JoinFlow;
import dev.meeyah.burrow.state.FriendsState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Burrow's replacement for the vanilla friends overlay.
 *
 * Deliberately built from standard widgets only (buttons, edit boxes, text
 * widgets) so the 26.x render pipeline draws everything for us — no custom
 * render code anywhere in M1. The layout mirrors the vanilla friends
 * overlay's LinearLayout + FrameLayout pattern.
 */
public class BurrowFriendsScreen extends Screen {
    private static final int LIST_WIDTH = 200;
    private static final int ROW_SPACING = 4;
    private static final int MAX_ROWS = 8;

    private final @Nullable Screen parent;
    private final FriendsState.Listener listener = this::onFriendsChanged;
    private String typedName = "";
    private int page = 0;
    private EditBox nameBox;
    private boolean showingErrorFlash = false;

    public BurrowFriendsScreen(@Nullable Screen parent) {
        super(Component.literal("Burrow — Friends"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Minecraft mc = this.minecraft;
        Font font = mc.font;

        LinearLayout layout = LinearLayout.vertical().spacing(ROW_SPACING);
        layout.defaultCellSetting().alignHorizontallyCenter();

        layout.addChild(new StringWidget(Component.literal("Burrow — Friends"), font));
        layout.addChild(new StringWidget(LIST_WIDTH, 9, statusComponent(), font));

        // ----- friends list (paged, one button per friend) -----
        List<FriendsState.Friend> friends = BurrowClient.friends().friends();
        int pages = Math.max(1, (friends.size() + MAX_ROWS - 1) / MAX_ROWS);
        if (this.page >= pages) {
            this.page = 0;
        }
        int from = this.page * MAX_ROWS;
        int to = Math.min(friends.size(), from + MAX_ROWS);
        if (friends.isEmpty()) {
            layout.addChild(new StringWidget(LIST_WIDTH, 9,
                    Component.literal("No friends yet — add someone below."), font));
        }
        for (FriendsState.Friend f : friends.subList(from, to)) {
            LinearLayout row = LinearLayout.horizontal().spacing(ROW_SPACING);
            row.addChild(Button.builder(friendLabel(f), b -> onFriendClicked(f))
                    .width(LIST_WIDTH - 24).build());
            row.addChild(Button.builder(Component.literal("x"), b -> onRemoveFriendClicked(f))
                    .width(20).build());
            layout.addChild(row);
        }
        if (pages > 1) {
            LinearLayout navRow = LinearLayout.horizontal().spacing(ROW_SPACING);
            if (this.page > 0) {
                navRow.addChild(Button.builder(Component.literal("< Prev"), b -> {
                    this.page--;
                    rebuildWidgets();
                }).width(96).build());
            }
            if (this.page < pages - 1) {
                navRow.addChild(Button.builder(Component.literal("Next >"), b -> {
                    this.page++;
                    rebuildWidgets();
                }).width(96).build());
            }
            layout.addChild(navRow);
        }

        // ----- pending requests (capped like the friends list) -----
        List<FriendsState.Request> requests = BurrowClient.friends().requests();
        if (!requests.isEmpty()) {
            layout.addChild(new StringWidget(LIST_WIDTH, 9,
                    Component.literal("Requests (" + requests.size() + ")"), font));
            int shownRequests = Math.min(requests.size(), MAX_ROWS);
            for (FriendsState.Request r : requests.subList(0, shownRequests)) {
                String label = (r.outgoing() ? "-> " : "<- ") + r.name();
                layout.addChild(Button.builder(Component.literal(label), b -> onRequestClicked(r))
                        .width(LIST_WIDTH).build());
            }
            if (requests.size() > shownRequests) {
                layout.addChild(new StringWidget(LIST_WIDTH, 9,
                        Component.literal("(+" + (requests.size() - shownRequests) + " more)"), font));
            }
        }

        // ----- add friend -----
        this.nameBox = new EditBox(font, 144, 20, Component.literal("Add friend"));
        this.nameBox.setMaxLength(32);
        this.nameBox.setHint(Component.literal("Profile name"));
        this.nameBox.setValue(this.typedName);
        LinearLayout addRow = LinearLayout.horizontal().spacing(ROW_SPACING);
        addRow.addChild(this.nameBox);
        addRow.addChild(Button.builder(Component.literal("Send"), b -> sendRequest(this.nameBox))
                .width(52).build());
        layout.addChild(addRow);

        // ----- share this world (only while in a singleplayer world) -----
        if (mc.getSingleplayerServer() != null) {
            boolean hosting = HostSession.isHosting();
            layout.addChild(Button.builder(
                            Component.literal(hosting ? "Close world to friends" : "Open world to friends"),
                            b -> {
                                if (HostSession.isHosting()) {
                                    HostSession.stop();
                                } else {
                                    HostSession.start();
                                }
                                rebuildWidgets();
                            })
                    .width(LIST_WIDTH).build());
        } else {
            // Left the world without closing hosting: clean up now.
            HostSession.ensureStopped();
        }

        layout.addChild(Button.builder(Component.literal("Done"), b -> onClose())
                .width(LIST_WIDTH).build());

        layout.arrangeElements();
        FrameLayout.alignInRectangle(layout, this.getRectangle(), 0.5F, 0.5F);
        layout.visitWidgets(this::addRenderableWidget);
        setInitialFocus(this.nameBox);
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
            sendRequest(this.nameBox);
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

    /** Capture whatever is currently typed so a live rebuild can't wipe it. */
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

    private void onFriendClicked(FriendsState.Friend friend) {
        // A hosting friend is joinable through the Burrow relay; everything
        // else on the card is informational for now.
        if (friend.hosting() && BurrowClient.connection() != null) {
            JoinFlow.begin(friend);
        }
    }

    private void onRemoveFriendClicked(FriendsState.Friend friend) {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(new RemoveFriendScreen(this, friend));
        }
    }

    private void onRequestClicked(FriendsState.Request request) {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(new RequestActionScreen(this, request));
        }
    }

    private void sendRequest(EditBox nameBox) {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            return;
        }
        this.typedName = "";
        nameBox.setValue("");
        BurrowClient.connection().addFriend(name);
    }

    private Component statusComponent() {
        // Server errors surface for a few seconds right in the status line.
        String flash = BurrowClient.friends().errorFlash();
        this.showingErrorFlash = (flash != null);
        if (flash != null) {
            return Component.literal("⚠ " + flash);
        }
        BurrowConnection connection = BurrowClient.connection();
        BurrowConnection.Status status =
                connection == null ? BurrowConnection.Status.OFFLINE : connection.status();
        String self = BurrowClient.friends().selfName();
        return switch (status) {
            case CONNECTED -> Component.literal("Connected as " + (self == null ? "?" : self));
            case CONNECTING -> Component.literal("Connecting...");
            case ERROR -> Component.literal("Offline (connection error) — retrying");
            case OFFLINE -> Component.literal("Offline — server unreachable");
        };
    }

    private static Component friendLabel(FriendsState.Friend f) {
        String presence;
        if (f.hosting()) {
            presence = "hosting " + (f.worldName() == null ? "a world" : f.worldName());
        } else if ("online".equalsIgnoreCase(f.presence())) {
            presence = "online";
        } else {
            presence = "offline";
        }
        return Component.literal(f.name() + " — " + presence);
    }

    /** Small all-widget action screen for a single friend request. */
    private static class RequestActionScreen extends Screen {
        private final Screen parent;
        private final FriendsState.Request request;

        RequestActionScreen(Screen parent, FriendsState.Request request) {
            super(Component.literal("Burrow — Request"));
            this.parent = parent;
            this.request = request;
        }

        @Override
        protected void init() {
            LinearLayout layout = LinearLayout.vertical().spacing(ROW_SPACING);
            layout.addChild(new StringWidget(LIST_WIDTH, 9, Component.literal(this.request.name()), this.minecraft.font));
            layout.addChild(new StringWidget(LIST_WIDTH, 9,
                    Component.literal(this.request.outgoing() ? "Outgoing request" : "Incoming request"), this.minecraft.font));
            if (this.request.outgoing()) {
                layout.addChild(Button.builder(Component.literal("Cancel request"), b -> {
                    BurrowClient.connection().declineRequest(this.request.uuid());
                    onClose();
                }).width(LIST_WIDTH).build());
            } else {
                layout.addChild(Button.builder(Component.literal("Accept"), b -> {
                    BurrowClient.connection().acceptRequest(this.request.uuid());
                    onClose();
                }).width(LIST_WIDTH).build());
                layout.addChild(Button.builder(Component.literal("Decline"), b -> {
                    BurrowClient.connection().declineRequest(this.request.uuid());
                    onClose();
                }).width(LIST_WIDTH).build());
            }
            layout.addChild(Button.builder(Component.literal("Back"), b -> onClose()).width(LIST_WIDTH).build());
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

    /** Small confirmation screen before removing a friend. */
    private static class RemoveFriendScreen extends Screen {
        private final Screen parent;
        private final FriendsState.Friend friend;

        RemoveFriendScreen(Screen parent, FriendsState.Friend friend) {
            super(Component.literal("Burrow — Remove Friend"));
            this.parent = parent;
            this.friend = friend;
        }

        @Override
        protected void init() {
            LinearLayout layout = LinearLayout.vertical().spacing(ROW_SPACING);
            layout.addChild(new StringWidget(LIST_WIDTH, 9,
                    Component.literal("Remove " + this.friend.name() + "?"), this.minecraft.font));
            layout.addChild(Button.builder(Component.literal("Remove friend"), b -> {
                BurrowClient.connection().removeFriend(this.friend.uuid());
                onClose();
            }).width(LIST_WIDTH).build());
            layout.addChild(Button.builder(Component.literal("Cancel"), b -> onClose())
                    .width(LIST_WIDTH).build());
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
