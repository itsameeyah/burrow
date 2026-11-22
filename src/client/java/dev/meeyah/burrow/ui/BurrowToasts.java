package dev.meeyah.burrow.ui;

import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.config.BurrowConfig;
import dev.meeyah.burrow.session.HostSession;
import dev.meeyah.burrow.session.JoinFlow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Vanilla toast notifications for Burrow events (world invites, invite
 * requests, incoming friend requests, and accepted friend requests), plus a
 * one-key [Y] quick-action while an actionable notification toast is on screen.
 */
public final class BurrowToasts {
    private static final long TOAST_DISPLAY_MS = 8_000L;
    private static final float CHIME_PITCH = 0.78f;
    private static final float CHIME_VOLUME = 0.65f;

    public enum ActionKind { WORLD_INVITE, INVITE_REQUEST, FRIEND_REQUEST }

    private record ActivePrompt(ActionKind kind, UUID uuid, String name, long expiresAtMs) {}

    private static volatile @Nullable ActivePrompt activePrompt;

    private BurrowToasts() {}

    private static boolean isDoNotDisturb() {
        BurrowConfig cfg = BurrowClient.config();
        return cfg != null && cfg.doNotDisturb;
    }

    private static void playNotificationChime(Minecraft mc) {
        try {
            if (mc != null && mc.getSoundManager() != null) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(
                        SoundEvents.NOTE_BLOCK_CHIME.value(), CHIME_PITCH, CHIME_VOLUME));
            }
        } catch (Throwable ignored) {
        }
    }

    public static void showWorldInvite(UUID hostUuid, String hostName, String worldName, String invitedByName) {
        if (isDoNotDisturb()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) {
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
        mc.execute(() -> {
            playNotificationChime(mc);
            SystemToast.add(
                    mc.gui.toastManager(),
                    new SystemToast.SystemToastId(TOAST_DISPLAY_MS),
                    title,
                    body);
        });
    }

    public static void showInviteRequest(UUID fromUuid, String fromName) {
        if (isDoNotDisturb()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null || fromName == null || fromName.isBlank()) {
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
                        : fromName + " wants to join · Press O");
        mc.execute(() -> {
            playNotificationChime(mc);
            SystemToast.add(
                    mc.gui.toastManager(),
                    new SystemToast.SystemToastId(TOAST_DISPLAY_MS),
                    title,
                    body);
        });
    }

    public static void showFriendRequest(UUID fromUuid, String fromName) {
        if (isDoNotDisturb()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null || fromName == null || fromName.isBlank()) {
            return;
        }
        if (fromUuid != null) {
            activePrompt = new ActivePrompt(
                    ActionKind.FRIEND_REQUEST, fromUuid, fromName,
                    System.currentTimeMillis() + TOAST_DISPLAY_MS);
        }
        Component title = Component.literal("Friend Request");
        Component body = Component.literal(fromName + " wants to be friends · [Y] Accept");
        mc.execute(() -> {
            playNotificationChime(mc);
            SystemToast.add(
                    mc.gui.toastManager(),
                    new SystemToast.SystemToastId(TOAST_DISPLAY_MS),
                    title,
                    body);
        });
    }

    public static void showFriendAccepted(@Nullable UUID whoUuid, String name) {
        if (isDoNotDisturb()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null || name == null || name.isBlank()) {
            return;
        }
        Component title = Component.literal("Friend Added");
        Component body = Component.literal(name + " accepted your friend request!");
        mc.execute(() -> {
            playNotificationChime(mc);
            SystemToast.add(
                    mc.gui.toastManager(),
                    new SystemToast.SystemToastId(5_000L),
                    title,
                    body);
        });
    }

    public static void showInfo(String title, String body) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) return;
        mc.execute(() -> SystemToast.add(
                mc.gui.toastManager(),
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
