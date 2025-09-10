package icu.nyat.kusunoki.modpackupdater.updater.dto;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;

public class Manifest {
    @SerializedName("packId")
    public String packId;

    @SerializedName("version")
    public String version; // "latest"

    // Optional display name
    @SerializedName("displayName")
    public String displayName; // optional

    // Updated: target Minecraft version (e.g., "1.21.1") per API docs
    @SerializedName("mcVersion")
    public String mcVersion; // optional

    @SerializedName("loader")
    public Loader loader; // optional

    @SerializedName("files")
    public List<FileEntry> files = new ArrayList<>();

    // New: mods metadata list (optional)
    @SerializedName("mods")
    public List<ModEntry> mods = new ArrayList<>(); // optional

    // Metadata fields from server
    @SerializedName("createdAt")
    public String createdAt; // ISO-8601

    @SerializedName("channel")
    public String channel; // optional

    @SerializedName("description")
    public String description; // optional

    public static class FileEntry {
        @SerializedName("path")
        public String path;
        @SerializedName("sha256")
        public String sha256;
        @SerializedName("size")
        public Long size;
    }

    public static class ModEntry {
        @SerializedName("path")
        public String path; // same as in files[] for the mod jar
        @SerializedName("id")
        public String id; // may be null
        @SerializedName("version")
        public String version; // may be null
        @SerializedName("name")
        public String name; // may be null
        @SerializedName("loader")
        public String loader; // fabric|forge|neoforge|quilt|null
    }

    public static class Loader {
        @SerializedName("name")
        public String name;
        @SerializedName("version")
        public String version;
    }
}
