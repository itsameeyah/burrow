package dev.meeyah.burrow.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ServerData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/**
 * Optional zero-dependency ViaFabricPlus integration (pure reflection so Burrow
 * runs standalone when ViaFabricPlus is not installed).
 *
 * Solves two problems on Burrow-hosted worlds:
 * 1. When ViaFabricPlus is set to Auto-Detect, its ConnectScreen mixin tries to
 *    open a raw TCP socket to {@code <uuid>.burrow:25565} to detect the server's
 *    protocol version, which fails with {@code UnknownHostException}. Setting
 *    the forced version on {@link ServerData} bypasses auto-detection cleanly.
 * 2. When a friend hosts on a different Minecraft version (e.g. 1.21.11 vs 26.2),
 *    Burrow can automatically target the host's known {@code gameVersion} via
 *    ViaFabricPlus and allow joining instead of greying out the Join button.
 */
public final class ViaFabricPlusCompat {
    private static final Logger LOG = LoggerFactory.getLogger("Burrow");

    private static volatile boolean initialized;
    private static volatile boolean available;
    private static volatile @Nullable Method getProtocolsMethod;
    private static volatile @Nullable Method getClosestMethod;
    private static volatile @Nullable Method isKnownMethod;
    private static volatile @Nullable Method getNameMethod;
    private static volatile @Nullable Method getIncludedVersionsMethod;
    private static volatile @Nullable Object nativeVersion;

    private ViaFabricPlusCompat() {}

    public static boolean isLoaded() {
        ensureInit();
        return available;
    }

    /**
     * Returns true when ViaFabricPlus is loaded and knows how to translate
     * {@code hostGameVersion} (or when {@code hostGameVersion} is blank).
     */
    public static boolean canTranslate(@Nullable String hostGameVersion) {
        if (!isLoaded()) {
            return false;
        }
        if (hostGameVersion == null || hostGameVersion.isBlank()) {
            return true;
        }
        return resolveProtocolVersion(hostGameVersion) != null;
    }

    /**
     * Forces ViaFabricPlus to use the host's Minecraft version on {@code data}
     * (falling back to {@code NATIVE_VERSION} if {@code hostGameVersion} is
     * absent or unrecognized). Always skips VFP's raw-socket auto-detector so
     * {@code <uuid>.burrow} never triggers an {@code UnknownHostException}.
     */
    public static void applyTargetVersion(@Nullable ServerData data, @Nullable String hostGameVersion) {
        if (data == null || !isLoaded()) {
            return;
        }
        try {
            Object target = resolveProtocolVersion(hostGameVersion);
            if (target == null) {
                target = nativeVersion;
            }
            if (target == null) {
                return;
            }
            Method forceMethod = findMethod(data.getClass(), "viaFabricPlus$forceVersion", 1);
            if (forceMethod != null) {
                forceMethod.invoke(data, target);
                Method passDirect = findMethod(data.getClass(), "viaFabricPlus$passDirectConnectScreen", 1);
                if (passDirect != null) {
                    passDirect.invoke(data, false);
                }
                LOG.debug("Set ViaFabricPlus target version for Burrow join to {} (host={})", target, hostGameVersion);
            }
        } catch (Throwable t) {
            LOG.warn("Failed to apply ViaFabricPlus target version (host={}): {}", hostGameVersion, t.toString());
        }
    }

    private static void ensureInit() {
        if (initialized) {
            return;
        }
        synchronized (ViaFabricPlusCompat.class) {
            if (initialized) {
                return;
            }
            try {
                if (FabricLoader.getInstance().isModLoaded("viafabricplus")) {
                    Class<?> pvClass = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
                    getProtocolsMethod = pvClass.getMethod("getProtocols");
                    getClosestMethod = pvClass.getMethod("getClosest", String.class);
                    isKnownMethod = pvClass.getMethod("isKnown");
                    getNameMethod = pvClass.getMethod("getName");
                    try {
                        getIncludedVersionsMethod = pvClass.getMethod("getIncludedVersions");
                    } catch (NoSuchMethodException ignored) {
                        getIncludedVersionsMethod = null;
                    }
                    nativeVersion = resolveNativeVersion();
                    available = true;
                    LOG.info("Burrow detected ViaFabricPlus — automatic version routing enabled");
                }
            } catch (Throwable t) {
                LOG.debug("ViaFabricPlus reflection init skipped: {}", t.toString());
                available = false;
            }
            initialized = true;
        }
    }

    private static @Nullable Object resolveNativeVersion() {
        String[] candidates = {
                "com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslationImpl",
                "com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator",
                "de.florianmichael.viafabricplus.protocoltranslator.ProtocolTranslator"
        };
        for (String clsName : candidates) {
            try {
                Class<?> cls = Class.forName(clsName);
                Field f = cls.getField("NATIVE_VERSION");
                Object val = f.get(null);
                if (val != null) {
                    return val;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static @Nullable Object resolveProtocolVersion(@Nullable String version) {
        if (version == null || version.isBlank()) {
            return nativeVersion;
        }
        String trimmed = version.trim();
        try {
            // 1. Exact match against ProtocolVersion name or included version set.
            if (getProtocolsMethod != null && getNameMethod != null) {
                Object listObj = getProtocolsMethod.invoke(null);
                if (listObj instanceof List<?> list) {
                    for (Object pv : list) {
                        Object nameObj = getNameMethod.invoke(pv);
                        if (nameObj instanceof String name && name.equalsIgnoreCase(trimmed)) {
                            return pv;
                        }
                        if (getIncludedVersionsMethod != null) {
                            Object incObj = getIncludedVersionsMethod.invoke(pv);
                            if (incObj instanceof Collection<?> inc) {
                                for (Object item : inc) {
                                    if (item instanceof String s && s.equalsIgnoreCase(trimmed)) {
                                        return pv;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // 2. Fallback to ProtocolVersion.getClosest(String).
            if (getClosestMethod != null && isKnownMethod != null) {
                Object closest = getClosestMethod.invoke(null, trimmed);
                if (closest != null && Boolean.TRUE.equals(isKnownMethod.invoke(closest))) {
                    return closest;
                }
            }
        } catch (Throwable t) {
            LOG.debug("ViaFabricPlus version lookup failed for '{}': {}", trimmed, t.toString());
        }
        return null;
    }

    private static @Nullable Method findMethod(Class<?> cls, String name, int paramCount) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                return m;
            }
        }
        return null;
    }
}
