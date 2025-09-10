package icu.nyat.kusunoki.modpackupdater.updater;

/**
 * Listener for reporting update status and current file being downloaded to the UI.
 */
@FunctionalInterface
public interface UpdateProgressListener {
    void updateStatus(String message);
}

