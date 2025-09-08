package icu.nyat.kusunoki.modpackupdater.updater.dto;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

public class DiffResponse {
    @SerializedName("packId")
    public String packId;
    @SerializedName("version")
    public String version;
    @SerializedName("operations")
    public List<Operation> operations = new ArrayList<>();

    public static class Operation {
        @SerializedName("path")
        public String path;
        @SerializedName("op")
        public String op; // Add | Update | Delete | Keep
        @SerializedName("sha256")
        public String sha256;
        @SerializedName("size")
        public Long size;
    }
}

