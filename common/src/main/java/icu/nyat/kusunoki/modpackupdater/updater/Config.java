package icu.nyat.kusunoki.modpackupdater.updater;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import icu.nyat.kusunoki.modpackupdater.Constants;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @SerializedName("baseUrl")
    private String baseUrl = "http://localhost:8080";

    @SerializedName("packId")
    private String packId = "example-pack";

    @SerializedName("autoUpdate")
    private boolean autoUpdate = true;

    @SerializedName("includePaths")
    private String[] includePaths = new String[]{"mods", "config", "resourcepacks"};

    @SerializedName("timeoutSeconds")
    private int timeoutSeconds = 30;

    // New options specific to handling the 'config' folder when included
    // Do not overwrite locally modified config files by default (matches previous behavior)
    @SerializedName("overwriteModifiedConfigs")
    private boolean overwriteModifiedConfigs = false;

    // Overwrite unmanaged config files (those without a previously recorded server SHA). Default off.
    @SerializedName("overwriteUnmanagedConfigs")
    private boolean overwriteUnmanagedConfigs = false;

    // Delete extra config files (not in manifest) by default (matches previous behavior)
    @SerializedName("deleteExtraConfigs")
    private boolean deleteExtraConfigs = true;

    public static Path configFile(Path gameDir) {
        return gameDir.resolve("config").resolve("modpackupdater.json");
    }

    public static Config load(Path gameDir) {
        Path cfgPath = configFile(gameDir);
        try {
            if (Files.notExists(cfgPath)) {
                Files.createDirectories(cfgPath.getParent());
                Config def = new Config();
                def.save(cfgPath);
                Constants.LOG.info("Created default ModPackUpdater config at {}", cfgPath);
                return def;
            }
            try (Reader r = Files.newBufferedReader(cfgPath)) {
                Config c = GSON.fromJson(r, Config.class);
                if (c == null) c = new Config();
                return c;
            }
        } catch (IOException e) {
            Constants.LOG.error("Failed to load config: {}", e.toString());
            return new Config();
        }
    }

    public void save(Path path) throws IOException {
        try (Writer w = Files.newBufferedWriter(path)) {
            GSON.toJson(this, w);
        }
    }

    // convenience helper
    public void saveToDefault(Path gameDir) throws IOException {
        save(configFile(gameDir));
    }

    public String getBaseUrl() { return baseUrl; }
    public String getPackId() { return packId; }
    public boolean isAutoUpdate() { return autoUpdate; }
    public String[] getIncludePaths() { return includePaths; }
    public Duration getTimeout() { return Duration.ofSeconds(Math.max(5, timeoutSeconds)); }

    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void setPackId(String packId) { this.packId = packId; }
    public void setAutoUpdate(boolean autoUpdate) { this.autoUpdate = autoUpdate; }
    public void setIncludePaths(String[] includePaths) { this.includePaths = includePaths; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    // New getters/setters
    public boolean isOverwriteModifiedConfigs() { return overwriteModifiedConfigs; }
    public void setOverwriteModifiedConfigs(boolean overwriteModifiedConfigs) { this.overwriteModifiedConfigs = overwriteModifiedConfigs; }

    public boolean isOverwriteUnmanagedConfigs() { return overwriteUnmanagedConfigs; }
    public void setOverwriteUnmanagedConfigs(boolean overwriteUnmanagedConfigs) { this.overwriteUnmanagedConfigs = overwriteUnmanagedConfigs; }

    public boolean isDeleteExtraConfigs() { return deleteExtraConfigs; }
    public void setDeleteExtraConfigs(boolean deleteExtraConfigs) { this.deleteExtraConfigs = deleteExtraConfigs; }
}
