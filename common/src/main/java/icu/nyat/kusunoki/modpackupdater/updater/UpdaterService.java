package icu.nyat.kusunoki.modpackupdater.updater;

import icu.nyat.kusunoki.modpackupdater.Constants;

import java.nio.file.Path;

public class UpdaterService {
    private static volatile boolean started = false;

    public static synchronized void bootstrap(Path gameDir) {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> {
            try {
                // Try to apply any pending delete/replace from a previous run (e.g., locked files on Windows)
                PendingOps.applyPending(gameDir);
                icu.nyat.kusunoki.modpackupdater.updater.Config cfg = icu.nyat.kusunoki.modpackupdater.updater.Config.load(gameDir);
                if (!cfg.isAutoUpdate()) {
                    Constants.LOG.info("ModPackUpdater: auto update disabled; skipping.");
                    return;
                }
                Constants.LOG.info("ModPackUpdater: starting update check for pack {}...", cfg.getPackId());
                new icu.nyat.kusunoki.modpackupdater.updater.UpdateRunner(gameDir, cfg).run();
            } catch (Throwable t1) {
                Constants.LOG.error("ModPackUpdater updater failed: {}", t1.toString());
            }
        }, "ModPackUpdater-Init");
        t.setDaemon(true);
        t.start();
    }
}
