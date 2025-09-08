package icu.nyat.kusunoki.modpackupdater.updater.dto;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;

public class Manifest {
    @SerializedName("packId")
    public String packId;

    @SerializedName("version")
    public String version; // "latest"

    // New: target Minecraft version (e.g., "1.21.1")
    @SerializedName("minecraft")
    public String minecraft; // optional

    @SerializedName("loader")
    public Loader loader; // optional

    @SerializedName("files")
    public List<FileEntry> files = new ArrayList<>();

    public static class FileEntry {
        @SerializedName("path")
        public String path;
        @SerializedName("sha256")
        public String sha256;
        @SerializedName("size")
        public Long size;
    }

    public static class Loader {
        @SerializedName("name")
        public String name;
        @SerializedName("version")
        public String version;
    }
}
