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

    /** Structured failure result so callers know whether to retry and back off. */
    public record AuthFailure(String message, boolean retryable, boolean rateLimited) {}

    /**
     * Asynchronously calls Mojang's sessionserver {@code joinServer} endpoint
     * on a dedicated daemon thread so the WebSocket and render threads never block.
     */
    public static void joinServerAsync(
            String serverId,
            Runnable onSuccess,
            Consumer<AuthFailure> onFailure
    ) {
        Thread worker = new Thread(() -> {
            if (serverId == null || serverId.isBlank()) {
                fail(onFailure, "Server sent an empty authentication challenge.", true, false);
                return;
            }

            Minecraft mc = safeMinecraft();
            if (mc == null) {
                fail(onFailure, "Minecraft client is not ready — retrying...", true, false);
                return;
            }

            User user = mc.getUser();
            UUID profileId = user != null ? user.getProfileId() : null;
            String token = user != null ? user.getAccessToken() : null;
            if (profileId == null || token == null || token.isBlank()) {
                fail(onFailure, "No active Minecraft session; sign in through the launcher.", false, false);
                return;
            }

            Services services = mc.services();
            SessionService sessionService = services != null ? services.sessionService() : null;
            if (sessionService == null) {
                fail(onFailure, "Minecraft session service unavailable — retrying...", true, false);
                return;
            }

            try {
                sessionService.joinServer(profileId, token, serverId);
                if (onSuccess != null) {
                    onSuccess.run();
                }
            } catch (AuthenticationUnavailableException e) {
                LOG.warn("Mojang session servers unreachable during Burrow auth", e);
                fail(onFailure, "Mojang auth servers unreachable — retrying...", true, false);
            } catch (InvalidCredentialsException e) {
                LOG.warn("Invalid Minecraft session during Burrow auth", e);
                fail(onFailure, "Invalid Minecraft session — restart launcher.", false, false);
            } catch (InsufficientPrivilegesException e) {
                LOG.warn("Multiplayer privileges missing for Mojang auth", e);
                fail(onFailure, "Multiplayer is disabled on this Microsoft account.", false, false);
            } catch (UserBannedException | ForcedUsernameChangeException e) {
                LOG.warn("Account restricted during Mojang auth", e);
                fail(onFailure, "This Minecraft account cannot authenticate with Mojang.", false, false);
            } catch (AuthenticationException e) {
                LOG.warn("Mojang authentication error during Burrow auth", e);
                if (isRateLimit(e)) {
                    fail(onFailure, "Mojang rate-limited (429) — retrying shortly...", true, true);
                } else {
                    String detail = e.getMessage();
                    String msg = (detail != null && !detail.isBlank())
                            ? "Mojang auth failed (" + detail + ") — retrying..."
                            : "Mojang auth failed — retrying...";
                    fail(onFailure, msg, true, false);
                }
            } catch (Exception e) {
                LOG.warn("Unexpected error during Mojang auth", e);
                fail(onFailure, "Mojang auth error — retrying...", true, isRateLimit(e));
            }
        }, "burrow-mojang-auth");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean isRateLimit(Throwable t) {
        Throwable cur = t;
        for (int depth = 0; cur != null && depth < 6; depth++) {
            String msg = cur.getMessage();
            if (msg != null && (msg.contains("429") || msg.toLowerCase().contains("too many requests"))) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
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

    private static void fail(@Nullable Consumer<AuthFailure> onFailure, String message, boolean retryable, boolean rateLimited) {
        if (onFailure != null) {
            onFailure.accept(new AuthFailure(message, retryable, rateLimited));
        }
    }
}
