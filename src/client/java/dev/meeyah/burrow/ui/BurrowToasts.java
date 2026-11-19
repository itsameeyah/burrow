package dev.meeyah.burrow.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Vanilla toast notifications for Burrow events (world invites, incoming
 * friend requests). Uses Minecraft's ToastManager so popups render reliably
 * on the Title Screen, in pause menus, and in-game.
 */
public final class BurrowToasts {
    private static final long INVITE_DISPLAY_MS = 7_000L;
    private static final long REQUEST_DISPLAY_MS = 5_000L;

    private BurrowToasts() {}

    public static void showWorldInvite(String hostName, String worldName, String invitedByName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) {
            return;
        }
        String sender = (invitedByName != null && !invitedByName.isBlank() && !invitedByName.equals(hostName))
                ? invitedByName + " (to " + hostName + "'s world)"
                : hostName;
        String world = (worldName == null || worldName.isBlank()) ? "a world" : worldName;
        Component title = Component.literal("World Invite — " + world);
        Component body = Component.literal(sender + " invited you · Press O to join");
        mc.execute(() -> SystemToast.add(
                mc.gui.toastManager(),
                new SystemToast.SystemToastId(INVITE_DISPLAY_MS),
                title,
                body));
    }

    public static void showFriendRequest(String fromName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null || fromName == null || fromName.isBlank()) {
            return;
        }
        Component title = Component.literal("Friend Request");
        Component body = Component.literal(fromName + " wants to be friends · Press O");
        mc.execute(() -> SystemToast.add(
                mc.gui.toastManager(),
                new SystemToast.SystemToastId(REQUEST_DISPLAY_MS),
                title,
                body));
    }
}
