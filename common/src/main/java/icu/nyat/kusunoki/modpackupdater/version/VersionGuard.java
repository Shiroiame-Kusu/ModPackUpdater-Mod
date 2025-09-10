package icu.nyat.kusunoki.modpackupdater.version;

import icu.nyat.kusunoki.modpackupdater.Constants;
import icu.nyat.kusunoki.modpackupdater.platform.Services;
import icu.nyat.kusunoki.modpackupdater.updater.Config;
import icu.nyat.kusunoki.modpackupdater.updater.api.ApiClient;
import icu.nyat.kusunoki.modpackupdater.updater.dto.Manifest;

import java.nio.file.Path;

public final class VersionGuard {
    private VersionGuard() {}

    private static volatile boolean mismatch = false;
    private static volatile String title = "";
    private static volatile String message = "";

    public static boolean hasMismatch() { return mismatch; }
    public static String getTitle() { return title; }
    public static String getMessage() { return message; }

    public static void checkNow(Path gameDir) {
        try {
            Config cfg = Config.load(gameDir);
            ApiClient api = new ApiClient(cfg);
            Manifest manifest = api.getManifest();
            if (manifest == null) return;

            // Resolve expected versions from manifest
            String expectedMc = safe(manifest.mcVersion);
            String expectedLoaderName = manifest.loader != null ? safe(manifest.loader.name) : "";
            String expectedLoaderVer = manifest.loader != null ? safe(manifest.loader.version) : "";

            // Resolve local
            String localMc = safe(Services.PLATFORM.getMinecraftVersion());
            String localLoaderName = safe(Services.PLATFORM.getLoaderId());
            String localLoaderVer = safe(Services.PLATFORM.getLoaderVersion());

            StringBuilder problems = new StringBuilder();
            if (!expectedMc.isEmpty() && !equalsLoose(expectedMc, localMc)) {
                problems.append("Minecraft version mismatch. Expected ")
                        .append(expectedMc).append(", got ").append(localMc).append('.');
            }
            if (!expectedLoaderName.isEmpty()) {
                if (!equalsLoose(expectedLoaderName, localLoaderName)) {
                    if (problems.length() > 0) problems.append('\n');
                    problems.append("Loader mismatch. Expected ")
                            .append(expectedLoaderName).append(", got ").append(localLoaderName).append('.');
                } else if (!expectedLoaderVer.isEmpty() && !equalsLoose(expectedLoaderVer, localLoaderVer)) {
                    if (problems.length() > 0) problems.append('\n');
                    problems.append("Loader version mismatch. Expected ")
                            .append(expectedLoaderVer).append(", got ").append(localLoaderVer).append('.');
                }
            } else if (!expectedLoaderVer.isEmpty() && !equalsLoose(expectedLoaderVer, localLoaderVer)) {
                if (problems.length() > 0) problems.append('\n');
                problems.append("Loader version mismatch. Expected ")
                        .append(expectedLoaderVer).append(", got ").append(localLoaderVer).append('.');
            }

            if (problems.length() > 0) {
                mismatch = true;
                title = "Incompatible environment";
                message = problems.toString();
                Constants.LOG.error("ModPackUpdater: {}\n{}", title, message);
            }
        } catch (Exception e) {
            // If manifest fetch fails, do not block startup; updater will log separately
            Constants.LOG.warn("VersionGuard: failed to check versions: {}", e.toString());
        }
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }

    private static boolean equalsLoose(String a, String b) {
        if (a.equals(b)) return true;
        // case-insensitive compare
        if (a.equalsIgnoreCase(b)) return true;
        // allow prefix match for versions like 1.21 vs 1.21.1 if specified that way
        if (a.length() > 0 && b.length() > 0) {
            String as = strip(a);
            String bs = strip(b);
            return as.equals(bs) || as.startsWith(bs + '.') || bs.startsWith(as + '.');
        }
        return false;
    }

    private static String strip(String s) {
        // remove leading/trailing v and whitespace
        String t = s.trim();
        if (t.startsWith("v") || t.startsWith("V")) t = t.substring(1);
        return t;
    }
}
