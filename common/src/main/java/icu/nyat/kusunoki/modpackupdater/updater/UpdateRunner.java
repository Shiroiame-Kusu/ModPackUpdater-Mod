package icu.nyat.kusunoki.modpackupdater.updater;

import icu.nyat.kusunoki.modpackupdater.Constants;
import icu.nyat.kusunoki.modpackupdater.updater.api.ApiClient;
import icu.nyat.kusunoki.modpackupdater.updater.dto.Manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class UpdateRunner implements Runnable {
    private final Path gameDir;
    private final Config cfg;
    private final boolean isWindows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    public UpdateRunner(Path gameDir, Config cfg) {
        this.gameDir = gameDir;
        this.cfg = cfg;
    }

    @Override
    public void run() {
        try {
            // Load previously installed index to protect user-added files from deletion
            InstalledIndex installedIndex = InstalledIndex.load(gameDir);
            Constants.LOG.info("ModPackUpdater: fetching server manifest...");
            ApiClient api = new ApiClient(cfg);
            Manifest manifest = api.getManifest();
            List<Manifest.FileEntry> serverFiles = manifest != null && manifest.files != null ? manifest.files : List.of();

            Constants.LOG.info("ModPackUpdater: scanning local files...");
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
            for (var fe : local) {
                if (fe == null || fe.path == null) continue;
                localMap.put(normKey(fe.path), fe);
            }

            // Special rule: config folder behavior is customizable
            boolean configIncluded = hasIncludeFolder(cfg.getIncludePaths(), "config");
            boolean overwriteConfigMods = cfg.isOverwriteModifiedConfigs();
            boolean overwriteUnmanagedConfigs = cfg.isOverwriteUnmanagedConfigs();
            boolean deleteExtraConfigs = cfg.isDeleteExtraConfigs();

            List<Manifest.FileEntry> toFetch = new ArrayList<>();
            List<String> toDelete = new ArrayList<>();
            List<String> addedPaths = new ArrayList<>(); // track newly added files (for logs)
            long adds = 0, updates = 0, keeps = 0;

            // Determine adds/updates/keeps
            for (var e : serverMap.entrySet()) {
                String key = e.getKey();
                Manifest.FileEntry s = e.getValue();
                var l = localMap.get(key);
                boolean isConfigPath = configIncluded && isUnderFolder(s.path, "config");
                String prevSha = installedIndex.getSha(s.path);
                boolean managedBefore = prevSha != null && !prevSha.isBlank();
                boolean mismatch = l == null || l.sha256 == null || !l.sha256.equalsIgnoreCase(s.sha256);
                boolean modifiedByPlayer = managedBefore && l != null && l.sha256 != null && !l.sha256.equalsIgnoreCase(prevSha);

                if (l == null) {
                    // Missing -> add (including config)
                    adds++; toFetch.add(s);
                    addedPaths.add(s.path);
                } else if (mismatch) {
                    if (isConfigPath) {
                        if (!managedBefore) {
                            if (overwriteUnmanagedConfigs) { updates++; toFetch.add(s); }
                            else { keeps++; Constants.LOG.info("Keep unmanaged local config: {}", s.path); }
                        } else if (modifiedByPlayer && !overwriteConfigMods) {
                            keeps++; Constants.LOG.info("Keep modified local config: {}", s.path);
                        } else {
                            updates++; toFetch.add(s);
                        }
                    } else {
                        if (modifiedByPlayer) {
                            keeps++; Constants.LOG.info("Skip update (user modified): {}", s.path);
                        } else {
                            updates++; toFetch.add(s);
                        }
                    }
                } else {
                    keeps++;
                }
            }
            // Determine deletes (local files not on server)
            for (var e : localMap.entrySet()) {
                if (!serverMap.containsKey(e.getKey())) {
                    String rel = e.getValue().path;
                    boolean isConfigPath = isUnderFolder(rel, "config");
                    if (isConfigPath) {
                        // For config/, delete extras only if enabled
                        if (deleteExtraConfigs) toDelete.add(rel); else Constants.LOG.info("Skip delete (extra config kept): {}", rel);
                    } else if (installedIndex.contains(rel)) {
                        // For all other configured paths: only delete if it was previously managed
                        toDelete.add(rel);
                    } else {
                        Constants.LOG.info("Skip delete (user file): {}", rel);
                    }
                }
            }

            if (toFetch.isEmpty() && toDelete.isEmpty()) {
                Constants.LOG.info("ModPackUpdater: up to date ({} keep).", keeps);
                return;
            }
            Constants.LOG.info("ModPackUpdater: changes -> add={}, update={}, delete={}, keep={}", adds, updates, toDelete.size(), keeps);

            // Download updates (per-file with concurrency), then apply deletes
            int updatedCount = 0;
            int failedCount = 0;
            List<String> addedOk = new ArrayList<>();
            List<String> updatedOk = new ArrayList<>();

            if (!toFetch.isEmpty()) {
                Path workDir = gameDir.resolve("modpackupdater");
                Files.createDirectories(workDir);
                int parallel = 4;
                ExecutorService pool = Executors.newFixedThreadPool(parallel, r -> {
                    Thread t = new Thread(r, "MPU-Download");
                    t.setDaemon(true);
                    return t;
                });
                try {
                    List<Callable<Boolean>> tasks = new ArrayList<>();
                    List<String> fetchPaths = new ArrayList<>();
                    for (Manifest.FileEntry s : toFetch) {
                        if (s == null || s.path == null) continue;
                        final String p = s.path;
                        final String sha = s.sha256;
                        fetchPaths.add(p);
                        tasks.add(() -> downloadSingleWithRetry(api, p, sha, workDir));
                    }
                    Set<String> addedSet = new HashSet<>(addedPaths);
                    List<Future<Boolean>> results = pool.invokeAll(tasks);
                    for (int i = 0; i < results.size(); i++) {
                        Future<Boolean> f = results.get(i);
                        String p = fetchPaths.get(i);
                        try {
                            if (Boolean.TRUE.equals(f.get())) {
                                updatedCount++;
                                if (addedSet.contains(p)) addedOk.add(p); else updatedOk.add(p);
                            } else {
                                failedCount++;
                            }
                        } catch (ExecutionException e) {
                            failedCount++;
                            Constants.LOG.warn("Download task failed: {}", e.getCause() != null ? e.getCause().toString() : e.toString());
                        }
                    }
                } finally {
                    pool.shutdownNow();
                }
            }

            // Apply deletes after updates attempt
            int deleted = 0;
            List<String> deletedOk = new ArrayList<>();
            if (!toDelete.isEmpty()) {
                for (String rel : toDelete) {
                    if (!icu.nyat.kusunoki.modpackupdater.updater.util.FileUtils.isIncluded(rel, cfg.getIncludePaths())) continue;
                    Path target = gameDir.resolve(rel).normalize();
                    if (!icu.nyat.kusunoki.modpackupdater.updater.util.FileUtils.isSafeChild(gameDir, target)) {
                        Constants.LOG.warn("Skip unsafe delete path: {}", rel);
                        continue;
                    }
                    try {
                        boolean ok = Files.deleteIfExists(target);
                        if (ok) {
                            deleted++;
                            deletedOk.add(rel);
                        }
                    } catch (IOException e) {
                        Constants.LOG.warn("Failed to delete {}: {}", rel, e.toString());
                        // Record pending delete to try on next launch (handles Windows file locks)
                        synchronized (PendingOps.class) {
                            PendingOps po = PendingOps.load(gameDir);
                            if (!po.delete.contains(rel)) po.delete.add(rel);
                            po.save(gameDir);
                        }
                    }
                }
            }
            if (!deletedOk.isEmpty()) Constants.LOG.info("Files deleted: {}", String.join(", ", deletedOk));

            // Update the installed index to reflect current managed files (those present in the manifest under includePaths)
            try {
                installedIndex.setFromManifest(
                        manifest != null ? manifest.packId : cfg.getPackId(),
                        manifest != null ? manifest.version : "latest",
                        serverFiles,
                        cfg.getIncludePaths()
                );
                installedIndex.save(gameDir);
            } catch (Exception ex) {
                Constants.LOG.warn("Failed to update installed index: {}", ex.toString());
            }

            // Summarize mod jar changes (added/deleted under mods/)
            try {
                List<String> modsAdded = new ArrayList<>();
                for (String p : addedPaths) if (isUnderFolder(p, "mods")) modsAdded.add(fileName(p));
                List<String> modsDeleted = new ArrayList<>();
                for (String p : deletedOk) if (isUnderFolder(p, "mods")) modsDeleted.add(fileName(p));
                if (!modsAdded.isEmpty()) {
                    Constants.LOG.info("Mods added: {}", String.join(", ", modsAdded));
                }
                if (!modsDeleted.isEmpty()) {
                    Constants.LOG.info("Mods deleted: {}", String.join(", ", modsDeleted));
                }
            } catch (Exception ignore) {}

            if (!addedOk.isEmpty()) Constants.LOG.info("Files added: {}", String.join(", ", addedOk));
            if (!updatedOk.isEmpty()) Constants.LOG.info("Files updated: {}", String.join(", ", updatedOk));
            Constants.LOG.info("ModPackUpdater: update done -> {} add/update ok, {} failed, {} delete", updatedCount, failedCount, deleted);
        } catch (Exception e) {
            Constants.LOG.error("ModPackUpdater: update failed", e);
        }
    }

    private static boolean hasIncludeFolder(String[] includePaths, String folderName) {
        if (includePaths == null) return false;
        for (String inc : includePaths) {
            if (inc == null || inc.isBlank()) continue;
            if (inc.replace('\\', '/').equalsIgnoreCase(folderName)) return true;
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

    private boolean downloadSingleWithRetry(ApiClient api, String relPath, String expectedSha, Path workDir) {
        Path dest = gameDir.resolve(relPath).normalize();
        if (!icu.nyat.kusunoki.modpackupdater.updater.util.FileUtils.isSafeChild(gameDir, dest)) {
            Constants.LOG.warn("Skipping unsafe path: {}", relPath);
            return false;
        }
        Path tmp = workDir.resolve(relPath + ".tmp").normalize();
        try {
            Files.createDirectories(Objects.requireNonNull(tmp.getParent()));
        } catch (IOException e) {
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
                    // Persist the staged file and record a pending replace to apply on next run
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
}
