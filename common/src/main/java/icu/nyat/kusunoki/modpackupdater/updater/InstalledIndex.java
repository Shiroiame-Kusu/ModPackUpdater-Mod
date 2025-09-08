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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class InstalledIndex {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @SerializedName("packId")
    public String packId;

    @SerializedName("version")
    public String version; // informational

    @SerializedName("files")
    public List<String> files = new ArrayList<>(); // relative paths

    // Last known server SHA-256 per relative path (normalized). Used to detect local modifications.
    @SerializedName("shas")
    public java.util.Map<String, String> shas = new java.util.HashMap<>();

    public static Path file(Path gameDir) {
        return gameDir.resolve("modpackupdater").resolve("installed.json");
    }

    public static InstalledIndex load(Path gameDir) {
        Path f = file(gameDir);
        try {
            if (Files.notExists(f)) return new InstalledIndex();
            try (Reader r = Files.newBufferedReader(f)) {
                InstalledIndex idx = GSON.fromJson(r, InstalledIndex.class);
                return idx != null ? idx : new InstalledIndex();
            }
        } catch (IOException e) {
            Constants.LOG.warn("Failed to read installed index: {}", e.toString());
            return new InstalledIndex();
        }
    }

    public void save(Path gameDir) {
        Path f = file(gameDir);
        try {
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f)) {
                GSON.toJson(this, w);
            }
        } catch (IOException e) {
            Constants.LOG.warn("Failed to save installed index: {}", e.toString());
        }
    }

    public boolean contains(String relPath) {
        String p = norm(relPath);
        for (String s : files) {
            if (norm(s).equals(p)) return true;
        }
        return false;
    }

    public String getSha(String relPath) {
        if (shas == null) return null;
        return shas.get(norm(relPath));
    }

    public static String norm(String p) {
        String n = p.replace('\\', '/');
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            n = n.toLowerCase(Locale.ROOT);
        }
        return n;
    }

    public void setFromList(String packId, String version, List<String> relPaths) {
        this.packId = packId;
        this.version = version;
        Set<String> uniq = new HashSet<>();
        this.files.clear();
        if (this.shas == null) this.shas = new java.util.HashMap<>();
        this.shas.clear();
        for (String p : relPaths) {
            if (p == null || p.isBlank()) continue;
            String n = norm(p);
            if (uniq.add(n)) this.files.add(n);
        }
    }

    public void setFromManifest(String packId, String version, java.util.List<icu.nyat.kusunoki.modpackupdater.updater.dto.Manifest.FileEntry> serverFiles, String[] includePaths) {
        this.packId = packId;
        this.version = version;
        Set<String> uniq = new HashSet<>();
        this.files.clear();
        if (this.shas == null) this.shas = new java.util.HashMap<>();
        this.shas.clear();
        if (serverFiles == null) return;
        for (var fe : serverFiles) {
            if (fe == null || fe.path == null) continue;
            if (!icu.nyat.kusunoki.modpackupdater.updater.util.FileUtils.isIncluded(fe.path, includePaths)) continue;
            String n = norm(fe.path);
            if (uniq.add(n)) this.files.add(n);
            if (fe.sha256 != null && !fe.sha256.isBlank()) {
                this.shas.put(n, fe.sha256);
            }
        }
    }
}
