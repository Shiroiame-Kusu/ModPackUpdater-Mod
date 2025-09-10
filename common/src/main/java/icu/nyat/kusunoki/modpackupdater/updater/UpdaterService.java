package icu.nyat.kusunoki.modpackupdater.updater;

import icu.nyat.kusunoki.modpackupdater.Constants;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

public class UpdaterService {
    private static volatile boolean started = false;
    // Session flag: once a successful update is applied, disable further updates until next launch
    private static volatile boolean updatesDisabledThisSession = false;

    public static boolean areUpdatesDisabled() { return updatesDisabledThisSession; }
    public static void markUpdatedThisSession() { updatesDisabledThisSession = true; }

    public static synchronized void bootstrap(Path gameDir) {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> {
            try {
                PendingOps.applyPending(gameDir);
                Config cfg = Config.load(gameDir);
                if (areUpdatesDisabled()) {
                    Constants.LOG.info("ModPackUpdater: updates disabled for this session; skipping auto check.");
                    return;
                }
                boolean clientEnv = isClientEnvironment();
                Constants.LOG.info("ModPackUpdater: starting startup check for pack {}... (env={})", cfg.getPackId(), clientEnv ? "client" : "server");
                // If client side -> checkOnly so we can prompt. If dedicated server -> apply immediately (no prompt UI available).
                boolean checkOnly = clientEnv;
                new UpdateRunner(gameDir, cfg, checkOnly).run();
            } catch (Throwable t1) {
                Constants.LOG.error("ModPackUpdater updater failed: {}", t1.toString());
            }
        }, "ModPackUpdater-Init");
        t.setDaemon(true);
        t.start();
    }

    // Pending prompt data to be shown once a screen is available
    public static final class PromptData {
        public final Path gameDir;
        public final Config cfg;
        public final List<String> adds;
        public final List<String> updates;
        public final List<String> deletes;
        public PromptData(Path gameDir, Config cfg, List<String> adds, List<String> updates, List<String> deletes) {
            this.gameDir = gameDir; this.cfg = cfg; this.adds = adds; this.updates = updates; this.deletes = deletes;
        }
    }
    private static volatile PromptData pendingPrompt;

    public static void showUpdatePrompt(Path gameDir, Config cfg, List<String> adds, List<String> updates, List<String> deletes) {
        // Store as pending first (so mixin can pick it up on TitleScreen init)
        pendingPrompt = new PromptData(gameDir, cfg, adds, updates, deletes);
        try {
            Class<?> prompt = Class.forName("icu.nyat.kusunoki.modpackupdater.ui.UpdatePrompt");
            Method m = prompt.getMethod("show", Path.class, Config.class, java.util.List.class, java.util.List.class, java.util.List.class);
            m.invoke(null, gameDir, cfg, adds, updates, deletes);
        } catch (Throwable t) {
            // ignore; mixin will consume pending later
        }
    }

    public static PromptData consumePendingPrompt() {
        PromptData d = pendingPrompt;
        pendingPrompt = null;
        return d;
    }

    private static boolean isClientEnvironment() {
        try {
            Class.forName("net.minecraft.client.Minecraft", false, UpdaterService.class.getClassLoader());
            return true; // client classes present
        } catch (Throwable ignored) {
            return false; // dedicated server (no client classes)
        }
    }
}
