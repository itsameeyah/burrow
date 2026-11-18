package dev.meeyah.burrow.ui;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.net.BurrowConnection;
import dev.meeyah.burrow.state.FriendsState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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

    private final FriendsState.Listener listener = this::onFriendsChanged;
    private String typedName = "";
    private int page = 0;

    public BurrowFriendsScreen() {
        super(Component.literal("Burrow — Friends"));
    }

    @Override
    protected void init() {
        Minecraft mc = this.minecraft;
        Font font = mc.font;

        LinearLayout layout = LinearLayout.vertical().spacing(ROW_SPACING);

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
            layout.addChild(Button.builder(friendLabel(f), b -> onFriendClicked(f))
                    .width(LIST_WIDTH).build());
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

        // ----- pending requests -----
        List<FriendsState.Request> requests = BurrowClient.friends().requests();
        if (!requests.isEmpty()) {
            layout.addChild(new StringWidget(LIST_WIDTH, 9,
                    Component.literal("Requests (" + requests.size() + ")"), font));
            for (FriendsState.Request r : requests) {
                String label = (r.outgoing() ? "-> " : "<- ") + r.name();
                layout.addChild(Button.builder(Component.literal(label), b -> onRequestClicked(r))
                        .width(LIST_WIDTH).build());
            }
        }

        // ----- add friend -----
        EditBox nameBox = new EditBox(font, 140, 18, Component.literal("Add friend"));
        nameBox.setMaxLength(32);
        nameBox.setHint(Component.literal("Profile name"));
        nameBox.setValue(this.typedName);
        LinearLayout addRow = LinearLayout.horizontal().spacing(ROW_SPACING);
        addRow.addChild(nameBox);
        addRow.addChild(Button.builder(Component.literal("Send"), b -> sendRequest(nameBox))
                .width(52).build());
        layout.addChild(addRow);

        layout.addChild(Button.builder(Component.literal("Done"), b -> onClose())
                .width(LIST_WIDTH).build());

        layout.visitWidgets(w -> addRenderableWidget(w));
        FrameLayout.alignInRectangle(layout, this.getRectangle(), 0.5F, 0.5F);
        setInitialFocus(nameBox);
    }

    @Override
    public void added() {
        BurrowClient.friends().addListener(this.listener);
    }

    @Override
    public void removed() {
        BurrowClient.friends().removeListener(this.listener);
    }

    private void onFriendsChanged() {
        if (this.minecraft == null || this.minecraft.gui == null) {
            return;
        }
        if (this.minecraft.gui.screen() instanceof BurrowFriendsScreen current) {
            current.rebuildWidgets();
        }
    }

    private void onFriendClicked(FriendsState.Friend friend) {
        // M2 wires this up: join the friend's hosted world when it goes live.
    }

    private void onRequestClicked(FriendsState.Request request) {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(new RequestActionScreen(request));
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
        private final FriendsState.Request request;

        RequestActionScreen(FriendsState.Request request) {
            super(Component.literal("Burrow — Request"));
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
            layout.visitWidgets(w -> addRenderableWidget(w));
            FrameLayout.alignInRectangle(layout, this.getRectangle(), 0.5F, 0.5F);
        }
    }
}
