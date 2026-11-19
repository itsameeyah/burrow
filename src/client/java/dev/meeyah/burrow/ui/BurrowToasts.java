package dev.meeyah.burrow.ui;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.session.JoinFlow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Vanilla toast notifications for Burrow events (world invites, invite
 * requests, and incoming friend requests), plus a one-key [Y] quick-action
 * while a notification toast is on screen.
 */
public final class BurrowToasts {
    private static final long TOAST_DISPLAY_MS = 8_000L;

    public enum ActionKind { WORLD_INVITE, INVITE_REQUEST, FRIEND_REQUEST }

    private record ActivePrompt(ActionKind kind, UUID uuid, String name, long expiresAtMs) {}

    private static volatile @Nullable ActivePrompt activePrompt;

    private BurrowToasts() {}

    public static void showWorldInvite(UUID hostUuid, String hostName, String worldName, String invitedByName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        String sender = (invitedByName != null && !invitedByName.isBlank() && !invitedByName.equals(hostName))
                ? invitedByName + " (to " + hostName + "'s world)"
                : hostName;
        String world = (worldName == null || worldName.isBlank()) ? "a world" : worldName;
        if (hostUuid != null) {
            activePrompt = new ActivePrompt(
                    ActionKind.WORLD_INVITE, hostUuid, hostName,
                    System.currentTimeMillis() + TOAST_DISPLAY_MS);
        }
        Component title = Component.literal("World Invite — " + world);
        Component body = Component.literal(sender + " invited you · [Y] Join");
        mc.execute(() -> SystemToast.add(
                mc.getToasts(),
                new SystemToast.SystemToastId(TOAST_DISPLAY_MS),
                title,
                body));
    }

    public static void showInviteRequest(UUID fromUuid, String fromName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || fromName == null || fromName.isBlank()) {
            return;
        }
        boolean canInviteNow = mc.getSingleplayerServer() != null;
        if (fromUuid != null && canInviteNow) {
            activePrompt = new ActivePrompt(
                    ActionKind.INVITE_REQUEST, fromUuid, fromName,
                    System.currentTimeMillis() + TOAST_DISPLAY_MS);
        }
        Component title = Component.literal("Invite Request");
        Component body = Component.literal(
                canInviteNow
                        ? fromName + " wants to join · [Y] Invite"
                        : fromName + " wants to join · Open Friends");
        mc.execute(() -> SystemToast.add(
                mc.getToasts(),
                new SystemToast.SystemToastId(TOAST_DISPLAY_MS),
                title,
                body));
    }

    public static void showFriendRequest(UUID fromUuid, String fromName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || fromName == null || fromName.isBlank()) {
            return;
        }
        if (fromUuid != null) {
            activePrompt = new ActivePrompt(
                    ActionKind.FRIEND_REQUEST, fromUuid, fromName,
                    System.currentTimeMillis() + TOAST_DISPLAY_MS);
        }
        Component title = Component.literal("Friend Request");
        Component body = Component.literal(fromName + " wants to be friends · [Y] Accept");
        mc.execute(() -> SystemToast.add(
                mc.getToasts(),
                new SystemToast.SystemToastId(TOAST_DISPLAY_MS),
                title,
                body));
    }

    public static void showInfo(String title, String body) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> SystemToast.add(
                mc.getToasts(),
                new SystemToast.SystemToastId(3_500L),
                Component.literal(title),
                Component.literal(body)));
    }

    /** True if a toast quick-action prompt is currently active on screen. */
    public static boolean hasActivePrompt() {
        ActivePrompt p = activePrompt;
        return p != null && System.currentTimeMillis() <= p.expiresAtMs();
    }

    /**
     * Executes and consumes the active toast quick-action when the player
     * presses [Y] while the toast is visible.
     */
    public static boolean triggerActivePrompt() {
        ActivePrompt p = activePrompt;
        if (p == null || System.currentTimeMillis() > p.expiresAtMs()) {
            activePrompt = null;
            return false;
        }
        activePrompt = null;
        switch (p.kind()) {
            case WORLD_INVITE -> {
                JoinFlow.beginByHostUuid(p.uuid());
                return true;
            }
            case INVITE_REQUEST -> {
                if (HostSession.inviteFriend(p.uuid())) {
                    showInfo("Invite Sent", "Invited " + p.name() + " to your world!");
                    return true;
                }
                return false;
            }
            case FRIEND_REQUEST -> {
                if (BurrowClient.connection() != null) {
                    BurrowClient.connection().acceptRequest(p.uuid());
                    showInfo("Friend Added", "You and " + p.name() + " are now friends!");
                    return true;
                }
                return false;
            }
        }
        return false;
    }
}
