package icu.nyat.kusunoki.modpackupdater.updater;

import icu.nyat.kusunoki.modpackupdater.Constants;
import icu.nyat.kusunoki.modpackupdater.updater.api.ApiClient;
import icu.nyat.kusunoki.modpackupdater.updater.dto.Manifest;
import icu.nyat.kusunoki.modpackupdater.updater.util.ModMetadataUtils;
import icu.nyat.kusunoki.modpackupdater.updater.util.ModMetadataUtils.ModInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;

public class UpdateRunner implements Runnable {
    private final Path gameDir;
    private final Config cfg;
    private final boolean isWindows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    private final boolean checkOnly; // check-only mode for auto run
    private final UpdateProgressListener listener; // optional listener for UI updates

    public UpdateRunner(Path gameDir, Config cfg) { this(gameDir, cfg, false, null); }
    public UpdateRunner(Path gameDir, Config cfg, boolean checkOnly) { this(gameDir, cfg, checkOnly, null); }
    public UpdateRunner(Path gameDir, Config cfg, UpdateProgressListener listener) { this(gameDir, cfg, false, listener); }
    public UpdateRunner(Path gameDir, Config cfg, boolean checkOnly, UpdateProgressListener listener) {
        this.gameDir = gameDir; this.cfg = cfg; this.checkOnly = checkOnly; this.listener = listener; }

    @Override public void run() { execute(); }

    /** Executes the update flow. @return true if no errors while applying (or check-only mode), false if apply failed. */
    public boolean execute() {
        try {
            status("Fetching manifest...");
            InstalledIndex installedIndex = InstalledIndex.load(gameDir);
            Constants.LOG.info("ModPackUpdater: fetching server manifest...");
            ApiClient api = new ApiClient(cfg);
            Manifest manifest = api.getManifest();
            List<Manifest.FileEntry> serverFiles = manifest != null && manifest.files != null ? manifest.files : List.of();

            // NEW: fetch mods list from separate endpoint (manifest no longer includes mods per updated API)
            status("Fetching mods metadata...");
            List<Manifest.ModEntry> serverMods;
            try {
                serverMods = api.getMods();
            } catch (Exception ex) {
                Constants.LOG.warn("Failed to fetch mods metadata: {}", ex.toString());
                serverMods = List.of();
            }

            // Build map of server mod metadata by path (normalized) and by mod name (lowercase) for convenience
            status("Processing manifest...");
            Map<String, Manifest.ModEntry> serverModsByPath = new HashMap<>();
            Map<String, Manifest.ModEntry> serverModsByName = new HashMap<>(); // name may be null
            for (Manifest.ModEntry me : serverMods) {
                if (me == null || me.path == null) continue;
                if (!icu.nyat.kusunoki.modpackupdater.updater.util.FileUtils.isIncluded(me.path, cfg.getIncludePaths())) continue;
                serverModsByPath.put(normKey(me.path), me);
                if (me.name != null && !me.name.isBlank()) {
                    serverModsByName.put(me.name.toLowerCase(java.util.Locale.ROOT), me);
                }
            }

            status("Scanning local files...");
            List<icu.nyat.kusunoki.modpackupdater.updater.dto.DiffRequest.FileEntry> local = icu.nyat.kusunoki.modpackupdater.updater.util.FileUtils
                    .computeLocalState(gameDir, cfg.getIncludePaths());

            // Build maps (case-insensitive on Windows)
            Map<String, Manifest.FileEntry> serverMap = new HashMap<>();
            for (Manifest.FileEntry fe : serverFiles) {
                if (fe == null || fe.path == null) continue;
                if (!icu.nyat.kusunoki.modpackupdater.updater.util.FileUtils.isIncluded(fe.path, cfg.getIncludePaths())) continue;
                serverMap.put(normKey(fe.path), fe);
            }
            Map<String, icu.nyat.kusunoki.modpackupdater.updater.dto.DiffRequest.FileEntry> localMap = new HashMap<>();
            for (var fe : local) { if (fe == null || fe.path == null) continue; localMap.put(normKey(fe.path), fe); }

            // Build local mods metadata maps (by id and by name) plus per-path info for version decoration
            Map<String, icu.nyat.kusunoki.modpackupdater.updater.dto.DiffRequest.FileEntry> localModsById = new HashMap<>();
            Map<String, icu.nyat.kusunoki.modpackupdater.updater.dto.DiffRequest.FileEntry> localModsByName = new HashMap<>();
            Map<String, ModInfo> localModInfoByPath = new HashMap<>();
            for (var fe : local) {
                if (fe == null || fe.path == null) continue;
                if (!isUnderFolder(fe.path, "mods")) continue;
                try {
                    ModInfo mi = ModMetadataUtils.readModInfo(gameDir.resolve(fe.path));
                    if (mi != null) {
                        localModInfoByPath.put(fe.path, mi);
                        if (mi.id != null && !mi.id.isBlank()) localModsById.putIfAbsent(mi.id.toLowerCase(Locale.ROOT), fe);
                        if (mi.name != null && !mi.name.isBlank()) localModsByName.putIfAbsent(mi.name.toLowerCase(Locale.ROOT), fe);
                    }
                } catch (Exception ignored) {}
            }

            boolean configIncluded = hasIncludeFolder(cfg.getIncludePaths(), "config");
            boolean overwriteConfigMods = cfg.isOverwriteModifiedConfigs();
            boolean overwriteUnmanagedConfigs = cfg.isOverwriteUnmanagedConfigs();
            boolean deleteExtraConfigs = cfg.isDeleteExtraConfigs();

            List<Manifest.FileEntry> toFetch = new ArrayList<>();
            List<String> toDelete = new ArrayList<>();
            List<String> addedPaths = new ArrayList<>();
            long adds = 0, updates = 0, keeps = 0;
            Map<String,String> renameOrigins = new HashMap<>(); // newPath -> oldPath when renamed & re-download needed

            // Determine adds/updates/keeps
            for (var e : serverMap.entrySet()) {
                String key = e.getKey();
                Manifest.FileEntry s = e.getValue();
                var l = localMap.get(key); // direct path match first
                boolean isConfigPath = configIncluded && isUnderFolder(s.path, "config");
                String prevSha = installedIndex.getSha(s.path);
                boolean managedBefore = prevSha != null && !prevSha.isBlank();
                boolean matchedByName = false;
                String renamedFromPath = null;

                Manifest.ModEntry meForCurrent = null;
                if (isUnderFolder(s.path, "mods")) {
                    meForCurrent = serverModsByPath.get(key);
                    // If path not found locally, attempt name/id based match to detect rename
                    if (l == null && meForCurrent != null) {
                        icu.nyat.kusunoki.modpackupdater.updater.dto.DiffRequest.FileEntry alt = null;
                        if (meForCurrent.id != null && !meForCurrent.id.isBlank()) {
                            alt = localModsById.get(meForCurrent.id.toLowerCase(Locale.ROOT));
                        }
                        if (alt == null && meForCurrent.name != null && !meForCurrent.name.isBlank()) {
                            alt = localModsByName.get(meForCurrent.name.toLowerCase(Locale.ROOT));
                        }
                        if (alt != null) {
                            l = alt; // treat as if this were the matching local file
                            matchedByName = true;
                            if (!alt.path.equals(s.path)) renamedFromPath = alt.path; // rename detected
                        }
                    }
                }

                boolean mismatch; boolean modifiedByPlayer;
                if (isUnderFolder(s.path, "mods")) {
                    Manifest.ModEntry me = meForCurrent; // already looked up
                    String serverVer = me != null ? safe(me.version) : "";
                    if (l != null && !serverVer.isEmpty()) {
                        String localVer = safe(readLocalModVersion(gameDir.resolve(l.path)));
                        if (!localVer.isEmpty()) {
                            mismatch = !equalsVersionLoose(serverVer, localVer) || (renamedFromPath != null && !Objects.equals(l.sha256, s.sha256));
                            modifiedByPlayer = managedBefore && l.sha256 != null && !l.sha256.equalsIgnoreCase(prevSha);
                        } else { mismatch = l.sha256 == null || !l.sha256.equalsIgnoreCase(s.sha256); modifiedByPlayer = managedBefore && l.sha256 != null && !l.sha256.equalsIgnoreCase(prevSha); }
                    } else { mismatch = (l == null) || l.sha256 == null || !l.sha256.equalsIgnoreCase(s.sha256); modifiedByPlayer = managedBefore && l != null && l.sha256 != null && !l.sha256.equalsIgnoreCase(prevSha); }
                } else { mismatch = (l == null) || l.sha256 == null || !l.sha256.equalsIgnoreCase(s.sha256); modifiedByPlayer = managedBefore && l != null && l.sha256 != null && !l.sha256.equalsIgnoreCase(prevSha); }

                if (l == null) {
                    // Missing -> add
                    adds++; toFetch.add(s); addedPaths.add(s.path);
                } else if (mismatch) {
                    if (isConfigPath) {
                        if (!managedBefore) {
                            if (overwriteUnmanagedConfigs) { updates++; toFetch.add(s); } else { keeps++; Constants.LOG.info("Keep unmanaged local config: {}", s.path); }
                        } else if (modifiedByPlayer && !overwriteConfigMods) { keeps++; Constants.LOG.info("Keep modified local config: {}", s.path); }
                        else { updates++; toFetch.add(s); }
                    } else {
                        if (matchedByName && renamedFromPath != null && l.sha256 != null && l.sha256.equalsIgnoreCase(s.sha256)) {
                            // Pure rename with identical content: move instead of download
                            try {
                                Path from = gameDir.resolve(renamedFromPath).normalize();
                                Path to = gameDir.resolve(s.path).normalize();
                                Files.createDirectories(to.getParent());
                                Files.move(from, to);
                                keeps++; // treat as keep (renamed)
                                Constants.LOG.info("Renamed mod file {} -> {} (hash unchanged)", renamedFromPath, s.path);
                                // Record in installed index later by virtue of manifest path
                                continue; // skip further processing for this entry
                            } catch (Exception ex) {
                                Constants.LOG.warn("Failed to rename {} -> {}, will re-download: {}", renamedFromPath, s.path, ex.toString());
                                updates++; toFetch.add(s);
                                // schedule deletion of old path if still exists after download
                                if (!toDelete.contains(renamedFromPath)) toDelete.add(renamedFromPath);
                            }
                        } else if (matchedByName && renamedFromPath != null) {
                            // Rename with changed content -> download new, delete old afterwards
                            updates++; toFetch.add(s);
                            if (!toDelete.contains(renamedFromPath)) toDelete.add(renamedFromPath);
                            renameOrigins.put(s.path, renamedFromPath);
                            Constants.LOG.info("Mod renamed {} -> {} (content changed)", renamedFromPath, s.path);
                        } else if (modifiedByPlayer) {
                            keeps++; Constants.LOG.info("Skip update (user modified): {}", s.path);
                        } else {
                            updates++; toFetch.add(s);
                        }
                    }
                } else {
                    // l != null and not mismatch
                    if (matchedByName && renamedFromPath != null) {
                        // Same content & version but path differs and we failed earlier rename (hash matched). Already handled above, but guard.
                        adds++; toFetch.add(s); addedPaths.add(s.path);
                        if (!toDelete.contains(renamedFromPath)) toDelete.add(renamedFromPath);
                        Constants.LOG.info("Mod path changed {} -> {} (treat as add)", renamedFromPath, s.path);
                    } else {
                        keeps++;
                    }
                }
            }
            // Determine deletes
            for (var e2 : localMap.entrySet()) {
                if (!serverMap.containsKey(e2.getKey())) {
                    String rel = e2.getValue().path; boolean isConfigPath2 = isUnderFolder(rel, "config");
                    if (isConfigPath2) { if (deleteExtraConfigs) toDelete.add(rel); else Constants.LOG.info("Skip delete (extra config kept): {}", rel); }
                    else if (installedIndex.contains(rel)) toDelete.add(rel); else Constants.LOG.info("Skip delete (user file): {}", rel);
                }
            }

            if (toFetch.isEmpty() && toDelete.isEmpty()) { status("Already up to date"); Constants.LOG.info("ModPackUpdater: up to date ({} keep).", keeps); return true; }

            if (checkOnly) {
                status("Updates available");
                Constants.LOG.info("ModPackUpdater: auto-check detected changes -> add={}, update={}, delete={}, keep={}", adds, updates, toDelete.size(), keeps);
                if ((adds + updates + toDelete.size()) > 0 && !UpdaterService.areUpdatesDisabled()) {
                    java.util.List<String> addList = new java.util.ArrayList<>(addedPaths);
                    java.util.Set<String> addedSet = new java.util.HashSet<>(addedPaths);
                    java.util.List<String> updateList = new java.util.ArrayList<>();
                    for (Manifest.FileEntry fe : toFetch) { if (fe == null || fe.path == null) continue; if (!addedSet.contains(fe.path)) updateList.add(fe.path); }
                    java.util.List<String> deleteList = new java.util.ArrayList<>(toDelete);

                    // Decorate mod paths with name and versions old->new
                    addList = decorateListWithModInfo(addList, serverModsByPath, localModInfoByPath, renameOrigins, true, false);
                    updateList = decorateListWithModInfo(updateList, serverModsByPath, localModInfoByPath, renameOrigins, false, false);
                    deleteList = decorateListWithModInfo(deleteList, serverModsByPath, localModInfoByPath, renameOrigins, false, true);

                    UpdaterService.showUpdatePrompt(gameDir, cfg, addList, updateList, deleteList);
                }
                return true;
            }

            status("Downloading files...");
            Constants.LOG.info("ModPackUpdater: changes -> add={}, update={}, delete={}, keep={}", adds, updates, toDelete.size(), keeps);
            int updatedCount = 0; int failedCount = 0; List<String> addedOk = new ArrayList<>(); List<String> updatedOk = new ArrayList<>();
            Path workDir = gameDir.resolve("modpackupdater");
            if (!toFetch.isEmpty()) {
                Files.createDirectories(workDir);
                status("Downloading files (" + toFetch.size() + ")...");
                int parallel = 4; ExecutorService pool = Executors.newFixedThreadPool(parallel, r -> { Thread t = new Thread(r, "MPU-Download"); t.setDaemon(true); return t; });
                try {
                    List<Callable<Boolean>> tasks = new ArrayList<>(); List<String> fetchPaths = new ArrayList<>(); final int total = toFetch.size();
                    for (int i = 0; i < toFetch.size(); i++) {
                        Manifest.FileEntry s = toFetch.get(i); if (s == null || s.path == null) continue; final String p = s.path; final String sha = s.sha256; final int index = i; fetchPaths.add(p);
                        tasks.add(() -> { status("Downloading (" + (index + 1) + "/" + total + "): " + p); boolean ok = downloadSingleWithRetry(api, p, sha, workDir); if (ok) status("Downloaded (" + (index + 1) + "/" + total + "): " + p); return ok; });
                    }
                    Set<String> addedSet = new HashSet<>(addedPaths); List<Future<Boolean>> results = pool.invokeAll(tasks);
                    for (int i = 0; i < results.size(); i++) {
                        Future<Boolean> f = results.get(i); String p = fetchPaths.get(i);
                        try { if (Boolean.TRUE.equals(f.get())) { updatedCount++; if (addedSet.contains(p)) addedOk.add(p); else updatedOk.add(p); } else { failedCount++; } }
                        catch (ExecutionException e) { failedCount++; Constants.LOG.warn("Download task failed: {}", e.getCause() != null ? e.getCause().toString() : e.toString()); }
                    }
                } finally { pool.shutdownNow(); }
            }
            int deleted = 0; List<String> deletedOk = new ArrayList<>();
            if (!toDelete.isEmpty()) {
                status("Deleting removed files...");
                for (String rel : toDelete) {
                    if (!icu.nyat.kusunoki.modpackupdater.updater.util.FileUtils.isIncluded(rel, cfg.getIncludePaths())) continue;
                    Path target = gameDir.resolve(rel).normalize(); if (!icu.nyat.kusunoki.modpackupdater.updater.util.FileUtils.isSafeChild(gameDir, target)) { Constants.LOG.warn("Skip unsafe delete path: {}", rel); continue; }
                    try { boolean ok = Files.deleteIfExists(target); if (ok) { deleted++; deletedOk.add(rel); } }
                    catch (IOException e) { Constants.LOG.warn("Failed to delete {}: {}", rel, e.toString()); synchronized (PendingOps.class) { PendingOps po = PendingOps.load(gameDir); if (!po.delete.contains(rel)) po.delete.add(rel); po.save(gameDir); } }
                }
            }
            if (!deletedOk.isEmpty()) Constants.LOG.info("Files deleted: {}", String.join(", ", deletedOk));
            status("Finalizing...");
            try {
                installedIndex.setFromManifest( manifest != null ? manifest.packId : cfg.getPackId(), manifest != null ? manifest.version : "latest", serverFiles, cfg.getIncludePaths());
                installedIndex.save(gameDir);
            } catch (Exception ex) { Constants.LOG.warn("Failed to update installed index: {}", ex.toString()); }
            try {
                List<String> modsAdded = new ArrayList<>(); for (String p : addedPaths) if (isUnderFolder(p, "mods")) modsAdded.add(fileName(p));
                List<String> modsDeleted = new ArrayList<>(); for (String p : deletedOk) if (isUnderFolder(p, "mods")) modsDeleted.add(fileName(p));
                if (!modsAdded.isEmpty()) { Constants.LOG.info("Mods added: {}", String.join(", ", modsAdded)); }
                if (!modsDeleted.isEmpty()) { Constants.LOG.info("Mods deleted: {}", String.join(", ", modsDeleted)); }
            } catch (Exception ignore) {}
            if (!addedOk.isEmpty()) Constants.LOG.info("Files added: {}", String.join(", ", addedOk));
            if (!updatedOk.isEmpty()) Constants.LOG.info("Files updated: {}", String.join(", ", updatedOk));
            Constants.LOG.info("ModPackUpdater: update done -> {} add/update ok, {} failed, {} delete", updatedCount, failedCount, deleted);
            boolean success = failedCount == 0; status(success ? "Update complete" : "Update finished with errors");
            if (success) { UpdaterService.markUpdatedThisSession(); }
            return success;
        } catch (Exception e) {
            status("Update failed");
            Constants.LOG.error("ModPackUpdater: update failed", e);
            return false;
        }
    }

    private void status(String s) { if (listener != null) { try { listener.updateStatus(s); } catch (Exception ignored) {} } }

    private static boolean hasIncludeFolder(String[] includePaths, String folderName) {
        if (includePaths == null) return false;
        String target = folderName.replace('\\', '/');
        if (target.endsWith("/")) target = target.substring(0, target.length() - 1);
        for (String inc : includePaths) {
            if (inc == null || inc.isBlank()) continue;
            String norm = inc.replace('\\', '/');
            if (norm.endsWith("/")) norm = norm.substring(0, norm.length() - 1);
            if (norm.equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    private static boolean isUnderFolder(String relativePath, String folderName) {
        String p = relativePath.replace('\\', '/');
        if (p.equalsIgnoreCase(folderName)) return true;
        String prefix = folderName.endsWith("/") ? folderName : folderName + "/";
        return p.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private String normKey(String p) {
        String n = p.replace('\\', '/');
        return isWindows ? n.toLowerCase(java.util.Locale.ROOT) : n;
    }

    private List<String> decorateListWithModInfo(List<String> paths,
                                                 Map<String, Manifest.ModEntry> serverModsByPath,
                                                 Map<String, ModInfo> localModInfoByPath,
                                                 Map<String,String> renameOrigins,
                                                 boolean isAdd,
                                                 boolean isDelete) {
        List<String> out = new ArrayList<>(paths.size());
        for (String p : paths) {
            if (p != null && isUnderFolder(p, "mods")) {
                Manifest.ModEntry me = serverModsByPath.get(normKey(p));
                String name = me != null && me.name != null && !me.name.isBlank() ? me.name : fileName(p);
                String newVer = me != null ? safe(me.version) : null;
                String oldPath = renameOrigins.getOrDefault(p, p);
                ModInfo localInfo = localModInfoByPath.get(oldPath);
                String oldVer = localInfo != null ? localInfo.version : null;
                if (isAdd && oldVer == null) oldVer = "?"; // Added mod had no previous version
                if (isDelete) { newVer = "removed"; }
                String decorated = name + " " + (oldVer != null && !oldVer.isBlank()? oldVer : "?") + "-->" + (newVer != null && !newVer.isBlank()? newVer : "?") + " " + p;
                out.add(decorated);
            } else {
                out.add(p);
            }
        }
        return out;
    }

    private boolean downloadSingleWithRetry(ApiClient api, String relPath, String expectedSha, Path workDir) {
        Path dest = gameDir.resolve(relPath).normalize();
        if (!icu.nyat.kusunoki.modpackupdater.updater.util.FileUtils.isSafeChild(gameDir, dest)) {
            Constants.LOG.warn("Skipping unsafe path: {}", relPath);
            return false;
        }
        Path tmp = workDir.resolve(relPath + ".tmp").normalize();
        try { Files.createDirectories(Objects.requireNonNull(tmp.getParent())); } catch (IOException e) {
            Constants.LOG.warn("Failed to create directories for {}: {}", tmp, e.toString());
            return false;
        }
        int attempts = 3;
        long baseDelayMs = 250;
        for (int i = 1; i <= attempts; i++) {
            try {
                api.downloadFileToTemp(relPath, tmp);
                if (!verifyShaIfProvided(tmp, expectedSha)) {
                    throw new IOException("SHA256 mismatch for " + relPath);
                }
                Files.createDirectories(dest.getParent());
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Exception ex) {
                if (i == attempts) {
                    Constants.LOG.warn("Download {} failed after {} attempts", relPath, attempts, ex);
                    try {
                        Path staged = workDir.resolve("staged").resolve(relPath).normalize();
                        Files.createDirectories(staged.getParent());
                        if (Files.exists(tmp)) {
                            Files.move(tmp, staged, StandardCopyOption.REPLACE_EXISTING);
                        }
                        String fromRel = gameDir.relativize(staged).toString().replace('\\', '/');
                        synchronized (PendingOps.class) {
                            PendingOps po = PendingOps.load(gameDir);
                            po.replace.add(new PendingOps.Replace(fromRel, relPath));
                            po.save(gameDir);
                        }
                        Constants.LOG.info("Staged {} for replacement on next launch", relPath);
                    } catch (IOException ioe) {
                        try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
                    }
                    return false;
                }
                Constants.LOG.warn("Attempt {}/{} failed for {}: {}", i, attempts, relPath, ex.toString());
                long delay = baseDelayMs * (1L << (i - 1));
                try { Thread.sleep(delay); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
        return false;
    }

    private boolean verifyShaIfProvided(Path file, String expectedSha) throws IOException {
        if (expectedSha == null || expectedSha.isBlank()) return true;
        try {
            String actual = icu.nyat.kusunoki.modpackupdater.updater.util.FileUtils.sha256(file);
            boolean ok = expectedSha.equalsIgnoreCase(actual);
            if (!ok) {
                Constants.LOG.warn("Hash mismatch for {} expected={} actual={}", gameDir.relativize(file), expectedSha, actual);
            }
            return ok;
        } catch (Exception e) {
            throw new IOException("Failed to hash " + file + ": " + e.getMessage(), e);
        }
    }

    private static String fileName(String relPath) {
        String p = relPath.replace('\\', '/');
        int idx = p.lastIndexOf('/');
        return idx >= 0 ? p.substring(idx + 1) : p;
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }

    private static boolean equalsVersionLoose(String a, String b) {
        if (a.equals(b)) return true;
        if (a.equalsIgnoreCase(b)) return true;
        String as = stripV(a);
        String bs = stripV(b);
        if (as.equals(bs)) return true;
        return as.equalsIgnoreCase(bs);
    }

    private static String stripV(String s) {
        String t = s.trim();
        if (t.startsWith("v") || t.startsWith("V")) t = t.substring(1);
        return t;
    }

    private static String readLocalModVersion(Path file) {
        try {
            if (java.nio.file.Files.exists(file)) {
                return ModMetadataUtils.readModVersion(file);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
