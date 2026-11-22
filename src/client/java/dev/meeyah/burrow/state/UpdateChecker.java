package dev.meeyah.burrow.state;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.Blaze3D;
import dev.meeyah.burrow.BurrowClient;
import dev.meeyah.burrow.ui.BurrowToasts;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks for newer Burrow mod releases from both the Burrow signaling server
 * (pushed in {@code welcome} and available at {@code GET /version}) and GitHub
 * Releases, classifying updates into two tiers:
 * <ul>
 *   <li>{@link Severity#COMPATIBLE}: patch-level update ({@code 0.5.X}) — server is still compatible.</li>
 *   <li>{@link Severity#BREAKING}: minor or major update ({@code 0.X.0} / {@code X.0.0}) or below server {@code minModVersion}.</li>
 * </ul>
 */
public final class UpdateChecker {
    private static final Logger LOG = LoggerFactory.getLogger("Burrow");
    private static final String DEFAULT_DOWNLOAD_URL = "https://github.com/itsameeyah/burrow/releases/latest";
    private static final String GITHUB_LATEST_API = "https://api.github.com/repos/itsameeyah/burrow/releases/latest";

    public enum Severity { NONE, COMPATIBLE, BREAKING }

    public record SemVer(int major, int minor, int patch) implements Comparable<SemVer> {
        public static @Nullable SemVer parse(@Nullable String raw) {
            if (raw == null) return null;
            String s = raw.trim();
            if (s.startsWith("v") || s.startsWith("V")) {
                s = s.substring(1);
            }
            // Strip build metadata (+26.3) or prerelease (-beta.1)
            int plus = s.indexOf('+');
            if (plus >= 0) s = s.substring(0, plus);
            int dash = s.indexOf('-');
            if (dash >= 0) s = s.substring(0, dash);
            String[] parts = s.split("\\.");
            if (parts.length < 2) return null;
            try {
                int maj = Integer.parseInt(parts[0].trim());
                int min = Integer.parseInt(parts[1].trim());
                int pat = parts.length >= 3 ? Integer.parseInt(parts[2].trim()) : 0;
                if (maj < 0 || min < 0 || pat < 0) return null;
                return new SemVer(maj, min, pat);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Override
        public int compareTo(SemVer o) {
            int c = Integer.compare(this.major, o.major);
            if (c != 0) return c;
            c = Integer.compare(this.minor, o.minor);
            if (c != 0) return c;
            return Integer.compare(this.patch, o.patch);
        }

        @Override
        public String toString() {
            return this.major + "." + this.minor + "." + this.patch;
        }
    }

    public record UpdateInfo(
            Severity severity,
            String currentVersion,
            String latestVersion,
            String downloadUrl
    ) {
        public boolean isAvailable() {
            return this.severity != Severity.NONE;
        }

        public boolean isBreaking() {
            return this.severity == Severity.BREAKING;
        }
    }

    private static final Object LOCK = new Object();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean STARTED_ASYNC = new AtomicBoolean(false);

    private static @Nullable SemVer latestKnown = null;
    private static @Nullable SemVer minCompatible = null;
    private static String downloadUrl = DEFAULT_DOWNLOAD_URL;
    private static volatile UpdateInfo currentInfo = new UpdateInfo(Severity.NONE, "", "", DEFAULT_DOWNLOAD_URL);
    private static volatile @Nullable String lastToastedKey = null;
    private static volatile boolean compatibleBannerDismissed = false;
    private static volatile boolean breakingModalShown = false;

    private UpdateChecker() {}

    public static void addListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    public static UpdateInfo info() {
        return currentInfo;
    }

    public static boolean isCompatibleBannerDismissed() {
        return compatibleBannerDismissed;
    }

    public static void dismissCompatibleBanner() {
        compatibleBannerDismissed = true;
        notifyListeners();
    }

    public static boolean shouldShowBreakingModal() {
        return currentInfo.isBreaking() && !breakingModalShown;
    }

    public static void markBreakingModalShown() {
        breakingModalShown = true;
    }

    public static void openDownloadPage() {
        String url = currentInfo.downloadUrl();
        if (url == null || (!url.startsWith("https://") && !url.startsWith("http://"))) {
            url = DEFAULT_DOWNLOAD_URL;
        }
        try {
            Blaze3D.openUri(URI.create(url));
        } catch (Exception e) {
            LOG.warn("Failed to open update URL {}", url, e);
        }
    }

    /** Kicks off a one-time background check against the Burrow server /version and GitHub Releases. */
    public static void checkAsync() {
        if (!STARTED_ASYNC.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            fetchServerVersion(http);
            fetchGithubLatest(http);
        }, "burrow-update-check");
        t.setDaemon(true);
        t.start();
    }

    /** Called from BurrowConnection on OP_WELCOME and from the HTTP /version check. */
    public static void onServerVersionInfo(
            @Nullable String latestVersionStr,
            @Nullable String minVersionStr,
            @Nullable String url
    ) {
        SemVer latest = SemVer.parse(latestVersionStr);
        SemVer min = SemVer.parse(minVersionStr);
        mergeUpdateData(latest, min, url);
    }

    private static void fetchServerVersion(HttpClient http) {
        String base = BurrowClient.relayBase();
        if (base == null || base.isBlank()) {
            return;
        }
        String httpBase;
        if (base.startsWith("wss://")) {
            httpBase = "https://" + base.substring(6);
        } else if (base.startsWith("ws://")) {
            httpBase = "http://" + base.substring(5);
        } else if (base.startsWith("https://") || base.startsWith("http://")) {
            httpBase = base;
        } else {
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(httpBase + "/version"))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "Burrow-Mod/" + BurrowClient.modVersion())
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200 || res.body() == null) {
                return;
            }
            JsonObject obj = JsonParser.parseString(res.body()).getAsJsonObject();
            String latest = obj.has("latestModVersion") && !obj.get("latestModVersion").isJsonNull()
                    ? obj.get("latestModVersion").getAsString() : null;
            String min = obj.has("minModVersion") && !obj.get("minModVersion").isJsonNull()
                    ? obj.get("minModVersion").getAsString() : null;
            String url = obj.has("downloadUrl") && !obj.get("downloadUrl").isJsonNull()
                    ? obj.get("downloadUrl").getAsString() : null;
            onServerVersionInfo(latest, min, url);
        } catch (Exception e) {
            LOG.debug("Burrow /version check skipped: {}", e.getMessage());
        }
    }

    private static void fetchGithubLatest(HttpClient http) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(GITHUB_LATEST_API))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Burrow-Mod/" + BurrowClient.modVersion())
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200 || res.body() == null) {
                return;
            }
            JsonObject obj = JsonParser.parseString(res.body()).getAsJsonObject();
            String tag = obj.has("tag_name") && !obj.get("tag_name").isJsonNull()
                    ? obj.get("tag_name").getAsString() : null;
            String htmlUrl = obj.has("html_url") && !obj.get("html_url").isJsonNull()
                    ? obj.get("html_url").getAsString() : null;
            SemVer parsed = SemVer.parse(tag);
            if (parsed != null) {
                mergeUpdateData(parsed, null, htmlUrl);
            }
        } catch (Exception e) {
            LOG.debug("GitHub release check skipped: {}", e.getMessage());
        }
    }

    private static void mergeUpdateData(@Nullable SemVer latest, @Nullable SemVer min, @Nullable String url) {
        String installedStr = BurrowClient.modVersion();
        SemVer installed = SemVer.parse(installedStr);
        if (installed == null) {
            return;
        }

        UpdateInfo updated;
        boolean shouldToast = false;
        synchronized (LOCK) {
            if (latest != null && (latestKnown == null || latest.compareTo(latestKnown) > 0)) {
                latestKnown = latest;
                if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                    downloadUrl = url;
                }
            } else if (url != null && (url.startsWith("https://") || url.startsWith("http://"))
                    && DEFAULT_DOWNLOAD_URL.equals(downloadUrl)) {
                downloadUrl = url;
            }
            if (min != null && (minCompatible == null || min.compareTo(minCompatible) > 0)) {
                minCompatible = min;
            }

            SemVer effectiveLatest = latestKnown;
            if (minCompatible != null && (effectiveLatest == null || minCompatible.compareTo(effectiveLatest) > 0)) {
                effectiveLatest = minCompatible;
            }

            Severity sev = classify(installed, effectiveLatest, minCompatible);
            String latestLabel = effectiveLatest != null ? effectiveLatest.toString() : installed.toString();
            updated = new UpdateInfo(sev, installed.toString(), latestLabel, downloadUrl);
            if (updated.equals(currentInfo)) {
                return;
            }
            currentInfo = updated;

            if (updated.isAvailable()) {
                String toastKey = updated.severity() + ":" + updated.latestVersion();
                if (!toastKey.equals(lastToastedKey)) {
                    lastToastedKey = toastKey;
                    shouldToast = true;
                }
            }
        }

        if (shouldToast) {
            LOG.info("Burrow update detected: {} -> {} ({})",
                    updated.currentVersion(), updated.latestVersion(), updated.severity());
            BurrowToasts.showUpdateAvailable(updated);
        }
        notifyListeners();
    }

    static Severity classify(SemVer current, @Nullable SemVer latest, @Nullable SemVer minRequired) {
        if (minRequired != null && current.compareTo(minRequired) < 0) {
            return Severity.BREAKING;
        }
        if (latest == null || latest.compareTo(current) <= 0) {
            return Severity.NONE;
        }
        if (latest.major() > current.major() || latest.minor() > current.minor()) {
            return Severity.BREAKING;
        }
        return Severity.COMPATIBLE;
    }

    private static void notifyListeners() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                for (Runnable r : LISTENERS) {
                    r.run();
                }
            });
        }
    }
}
