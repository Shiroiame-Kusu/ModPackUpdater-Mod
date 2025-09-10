package icu.nyat.kusunoki.modpackupdater.ui;

import icu.nyat.kusunoki.modpackupdater.updater.Config;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.List;

/**
 * Client-side entry point to show the update prompt screen from common updater code.
 */
public final class UpdatePrompt {
    private UpdatePrompt() {}

    public static void show(Path gameDir, Config cfg, List<String> adds, List<String> updates, List<String> deletes) {
        try {
            Minecraft mc = Minecraft.getInstance();
            // Delay a few client ticks so the background (world/panorama) has rendered at least once
            final int[] ticks = { 40 }; // ~20 ticks (~1s) delay
            mc.execute(new Runnable() {
                @Override
                public void run() {
                    if (ticks[0]-- > 0) {
                        // Re-queue for next client tick
                        mc.execute(this);
                        return;
                    }
                    // Open the prompt after delay
                    mc.setScreen(new UpdatePromptScreen(gameDir, cfg, adds, updates, deletes));
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
