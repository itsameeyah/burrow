package dev.meeyah.burrow.dev;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Offline dev identities, used only for local two-window testing.
 *
 * This is gated on FabricLoader's development-environment flag, which is only
 * true inside a gradle dev run — the shipped jar never activates it, and the
 * real Burrow server refuses "dev" hellos unless it was itself started with
 * dev mode explicitly enabled.
 */
public final class DevIdentity {
    private DevIdentity() {}

    public static boolean isDev() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    /**
     * Username of the running dev client. Set via the --username program
     * argument in the loom run configs (clientMeeyah / clientTestbunny).
     */
    public static String username() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.getUser() != null) {
                String name = mc.getUser().getName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
            // Too early in startup, or not on the client thread yet.
        }
        return "dev";
    }

    /**
     * The classic offline-profile UUID algorithm, so dev identities are
     * stable across restarts and match how offline-mode Minecraft derives
     * profile UUIDs.
     */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
