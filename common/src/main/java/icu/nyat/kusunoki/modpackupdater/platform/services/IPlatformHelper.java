package icu.nyat.kusunoki.modpackupdater.platform.services;

import java.nio.file.Path;

public interface IPlatformHelper {

    /**
     * Gets the name of the current platform
     *
     * @return The name of the current platform.
     */
    String getPlatformName();

    /**
     * Checks if a mod with the given id is loaded.
     *
     * @param modId The mod to check if it is loaded.
     * @return True if the mod is loaded, false otherwise.
     */
    boolean isModLoaded(String modId);

    /**
     * Check if the game is currently in a development environment.
     *
     * @return True if in a development environment, false otherwise.
     */
    boolean isDevelopmentEnvironment();

    /**
     * The root game directory (a.k.a. run directory).
     */
    Path getGameDirectory();

    /**
     * Gets the name of the environment type as a string.
     *
     * @return The name of the environment type.
     */
    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }

    /**
     * New: loader identification for version checks
     */
    default String getLoaderId() {
        return getPlatformName().toLowerCase(java.util.Locale.ROOT);
    }

    String getLoaderVersion();

    /**
     * New: MC version string (e.g., "1.21.1")
     */
    default String getMinecraftVersion() {
        try {
            return net.minecraft.SharedConstants.getCurrentVersion().getName();
        } catch (Throwable t) {
            return "";
        }
    }
}
