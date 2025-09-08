package icu.nyat.kusunoki.modpackupdater.updater.util;

import icu.nyat.kusunoki.modpackupdater.Constants;
import icu.nyat.kusunoki.modpackupdater.updater.dto.DiffRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class FileUtils {

    public static List<DiffRequest.FileEntry> computeLocalState(Path gameDir, String[] includePaths) throws IOException {
        List<DiffRequest.FileEntry> list = new ArrayList<>();
        Set<Path> roots = new HashSet<>();
        for (String inc : includePaths) {
            if (inc == null || inc.isBlank()) continue;
            Path p = gameDir.resolve(inc).normalize();
            if (Files.isDirectory(p) && isSafeChild(gameDir, p)) {
                roots.add(p);
            }
        }
        for (Path root : roots) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    String rel = gameDir.relativize(file).toString().replace('\\', '/');
                    try {
                        String sha = sha256(file);
                        DiffRequest.FileEntry fe = new DiffRequest.FileEntry(rel, sha, attrs.size());
                        list.add(fe);
                    } catch (Exception e) {
                        Constants.LOG.warn("Failed to hash {}: {}", rel, e.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return list;
    }

    public static boolean isIncluded(String relativePath, String[] includePaths) {
        String norm = relativePath.replace('\\', '/');
        for (String inc : includePaths) {
            if (inc == null || inc.isBlank()) continue;
            String prefix = inc.replace('\\', '/');
            if (!prefix.endsWith("/")) prefix += "/";
            if (norm.startsWith(prefix) || norm.equals(inc)) return true;
        }
        return false;
    }

    public static boolean isSafeChild(Path parent, Path child) {
        Path p = child.normalize();
        Path base = parent.toAbsolutePath().normalize();
        return p.toAbsolutePath().normalize().startsWith(base);
    }

    public static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file); DigestInputStream dis = new DigestInputStream(in, md)) {
            byte[] buf = new byte[8192];
            while (dis.read(buf) != -1) { /* read */ }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}

