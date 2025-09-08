package icu.nyat.kusunoki.modpackupdater.updater.dto;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

public class DiffRequest {
    @SerializedName("files")
    public List<FileEntry> files = new ArrayList<>();

    public DiffRequest() {}

    public DiffRequest(List<FileEntry> files) {
        if (files != null) this.files = files;
    }

    public static class FileEntry {
        @SerializedName("path")
        public String path;
        @SerializedName("sha256")
        public String sha256;
        @SerializedName("size")
        public Long size;

        public FileEntry() {}
        public FileEntry(String path, String sha256, Long size) {
            this.path = path;
            this.sha256 = sha256;
            this.size = size;
        }
    }
}

