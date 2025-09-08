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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class PendingOps {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @SerializedName("delete")
    public List<String> delete = new ArrayList<>(); // relative paths

    @SerializedName("replace")
    public List<Replace> replace = new ArrayList<>();

    public static class Replace {
        @SerializedName("from") public String from; // relative path of staged file
        @SerializedName("to") public String to;     // relative target path
        public Replace() {}
        public Replace(String from, String to) { this.from = from; this.to = to; }
    }

    public static Path file(Path gameDir) {
        return gameDir.resolve("modpackupdater").resolve("pending.json");
    }

    public static PendingOps load(Path gameDir) {
        Path f = file(gameDir);
        try {
            if (Files.notExists(f)) return new PendingOps();
            try (Reader r = Files.newBufferedReader(f)) {
                PendingOps po = GSON.fromJson(r, PendingOps.class);
                return po != null ? po : new PendingOps();
            }
        } catch (IOException e) {
            Constants.LOG.warn("Failed to read pending ops: {}", e.toString());
            return new PendingOps();
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
            Constants.LOG.warn("Failed to save pending ops: {}", e.toString());
        }
    }

    public static void applyPending(Path gameDir) {
        PendingOps po = load(gameDir);
        if (po.delete.isEmpty() && po.replace.isEmpty()) return;
        int delOk = 0, delFail = 0, repOk = 0, repFail = 0;

        // Apply deletes
        for (Iterator<String> it = po.delete.iterator(); it.hasNext();) {
            String rel = it.next();
            try {
                Path p = gameDir.resolve(rel).normalize();
                Files.deleteIfExists(p);
                delOk++;
                it.remove();
            } catch (IOException e) {
                delFail++;
            }
        }
        // Apply replaces
        for (Iterator<Replace> it = po.replace.iterator(); it.hasNext();) {
            Replace r = it.next();
            try {
                Path from = gameDir.resolve(r.from).normalize();
                Path to = gameDir.resolve(r.to).normalize();
                Files.createDirectories(to.getParent());
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                repOk++;
                it.remove();
            } catch (IOException e) {
                repFail++;
            }
        }

        if (delOk + repOk > 0) {
            Constants.LOG.info("ModPackUpdater: applied pending ops -> {} delete ok, {} replace ok ({} delete fail, {} replace fail)", delOk, repOk, delFail, repFail);
        }
        // Save updated list (keep failures for next run)
        po.save(gameDir);
    }
}

