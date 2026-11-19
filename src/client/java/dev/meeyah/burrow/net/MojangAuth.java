package dev.meeyah.burrow.net;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.ForcedUsernameChangeException;
import com.mojang.authlib.exceptions.InsufficientPrivilegesException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.authlib.exceptions.UserBannedException;
import com.mojang.authlib.minecraft.SessionService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.server.Services;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Mojang sessionserver authentication helper for Burrow signaling handshakes.
 *
 * Uses Minecraft's own logged-in {@link User} and {@link SessionService}
 * to answer the server's {@code auth.challenge} via Mojang's standard
 * {@code joinServer} call, proving ownership of the Minecraft account without
 * Burrow ever seeing the player's access token.
 */
public final class MojangAuth {
    private static final Logger LOG = LoggerFactory.getLogger("Burrow");

    private MojangAuth() {}

    /** Logged-in Minecraft username, or empty string if unavailable. */
    public static String username() {
        User user = currentUser();
        if (user == null) {
            return "";
        }
        String name = user.getName();
        return name != null ? name : "";
    }

    /** Logged-in Minecraft profile UUID, or {@code null} if unavailable. */
    public static @Nullable UUID profileId() {
        User user = currentUser();
        return user != null ? user.getProfileId() : null;
    }

    /** Logged-in Minecraft profile UUID as a string, or empty string if unavailable. */
    public static String profileIdString() {
        UUID id = profileId();
        return id != null ? id.toString() : "";
    }

    /** Logged-in Minecraft session access token, or {@code null} if unavailable. */
    public static @Nullable String accessToken() {
        User user = currentUser();
        if (user == null) {
            return null;
        }
        String token = user.getAccessToken();
        return (token == null || token.isBlank()) ? null : token;
    }

    /**
     * Asynchronously calls Mojang's sessionserver {@code joinServer} endpoint
     * on a dedicated daemon thread so the WebSocket and render threads never block.
     */
    public static void joinServerAsync(
            String serverId,
            Runnable onSuccess,
            Consumer<String> onFailure
    ) {
        Thread worker = new Thread(() -> {
            if (serverId == null || serverId.isBlank()) {
                fail(onFailure, "Server sent an empty authentication challenge.");
                return;
            }

            Minecraft mc = safeMinecraft();
            if (mc == null) {
                fail(onFailure, "Minecraft client is not ready to authenticate.");
                return;
            }

            User user = mc.getUser();
            UUID profileId = user != null ? user.getProfileId() : null;
            String token = user != null ? user.getAccessToken() : null;
            if (profileId == null || token == null || token.isBlank()) {
                fail(onFailure, "No active Minecraft session; sign in through the launcher and restart.");
                return;
            }

            Services services = mc.services();
            SessionService sessionService = services != null ? services.sessionService() : null;
            if (sessionService == null) {
                fail(onFailure, "Minecraft session service is unavailable.");
                return;
            }

            try {
                sessionService.joinServer(profileId, token, serverId);
                if (onSuccess != null) {
                    onSuccess.run();
                }
            } catch (AuthenticationUnavailableException e) {
                LOG.warn("Mojang session servers unreachable during Burrow auth", e);
                fail(onFailure, "Mojang authentication servers are unreachable. Try again shortly.");
            } catch (InvalidCredentialsException e) {
                LOG.warn("Invalid Minecraft session during Burrow auth", e);
                fail(onFailure, "Invalid Minecraft session. Restart your launcher and try again.");
            } catch (InsufficientPrivilegesException e) {
                LOG.warn("Multiplayer privileges missing for Mojang auth", e);
                fail(onFailure, "Multiplayer is disabled on this Microsoft account.");
            } catch (UserBannedException | ForcedUsernameChangeException e) {
                LOG.warn("Account restricted during Mojang auth", e);
                fail(onFailure, "This Minecraft account cannot authenticate with Mojang.");
            } catch (AuthenticationException e) {
                LOG.warn("Mojang authentication error during Burrow auth", e);
                String detail = e.getMessage();
                fail(onFailure, (detail != null && !detail.isBlank())
                        ? "Mojang authentication failed: " + detail
                        : "Mojang authentication failed.");
            } catch (Exception e) {
                LOG.warn("Unexpected error during Mojang auth", e);
                fail(onFailure, "Unexpected error during Mojang authentication.");
            }
        }, "burrow-mojang-auth");
        worker.setDaemon(true);
        worker.start();
    }

    private static @Nullable Minecraft safeMinecraft() {
        try {
            return Minecraft.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    private static @Nullable User currentUser() {
        Minecraft mc = safeMinecraft();
        return mc != null ? mc.getUser() : null;
    }

    private static void fail(@Nullable Consumer<String> onFailure, String message) {
        if (onFailure != null) {
            onFailure.accept(message);
        }
    }
}
