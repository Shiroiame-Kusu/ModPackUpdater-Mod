package icu.nyat.kusunoki.modpackupdater.updater.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModMetadataUtils {
    private ModMetadataUtils() {}

    /**
     * Best-effort extraction of a mod version string from a mod jar.
     * Supports Fabric (fabric.mod.json), Quilt (quilt.mod.json), and Forge/NeoForge (META-INF/mods.toml).
     * Returns null if no version can be determined.
     */
    public static String readModVersion(Path jarPath) {
        try (ZipFile zf = new ZipFile(jarPath.toFile())) {
            // Fabric
            String v = tryFabric(zf);
            if (v != null && !v.isBlank()) return v;
            // Quilt
            v = tryQuilt(zf);
            if (v != null && !v.isBlank()) return v;
            // NeoForge (1.21+ JSON metadata)
            v = tryNeoForgeJson(zf);
            if (v != null && !v.isBlank()) return v;
            // Forge/older NeoForge (TOML)
            v = tryForgeToml(zf);
            if (v != null && !v.isBlank()) return v;
        } catch (IOException ignored) {}
        return null;
    }

    private static String tryFabric(ZipFile zf) throws IOException {
        ZipEntry e = zf.getEntry("fabric.mod.json");
        if (e == null) return null;
        try (InputStream is = zf.getInputStream(e)) {
            JsonElement el = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            if (!el.isJsonObject()) return null;
            JsonObject obj = el.getAsJsonObject();
            JsonElement ve = obj.get("version");
            if (ve != null && ve.isJsonPrimitive()) return ve.getAsString();
        }
        return null;
    }

    private static String tryQuilt(ZipFile zf) throws IOException {
        ZipEntry e = zf.getEntry("quilt.mod.json");
        if (e == null) return null;
        try (InputStream is = zf.getInputStream(e)) {
            JsonElement el = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            if (!el.isJsonObject()) return null;
            JsonObject obj = el.getAsJsonObject();
            JsonElement ql = obj.get("quilt_loader");
            if (ql != null && ql.isJsonObject()) {
                JsonElement ve = ql.getAsJsonObject().get("version");
                if (ve != null && ve.isJsonPrimitive()) return ve.getAsString();
            }
            // Fallbacks (some mods may keep top-level id/version)
            JsonElement ve = obj.get("version");
            if (ve != null && ve.isJsonPrimitive()) return ve.getAsString();
        }
        return null;
    }

    private static String tryNeoForgeJson(ZipFile zf) throws IOException {
        ZipEntry e = zf.getEntry("neoforge.mods.json");
        if (e == null) return null;
        try (InputStream is = zf.getInputStream(e)) {
            JsonElement el = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            if (!el.isJsonObject()) return null;
            JsonObject obj = el.getAsJsonObject();
            // Prefer mods[0].version
            JsonElement modsEl = obj.get("mods");
            if (modsEl != null && modsEl.isJsonArray() && modsEl.getAsJsonArray().size() > 0) {
                JsonElement first = modsEl.getAsJsonArray().get(0);
                if (first.isJsonObject()) {
                    JsonElement ve = first.getAsJsonObject().get("version");
                    if (ve != null && ve.isJsonPrimitive()) return ve.getAsString();
                }
            }
            // Fallback to top-level version if present
            JsonElement ve = obj.get("version");
            if (ve != null && ve.isJsonPrimitive()) return ve.getAsString();
        }
        return null;
    }

    private static String tryForgeToml(ZipFile zf) throws IOException {
        ParsedForgeModsToml parsed = parseForgeModsToml(zf);
        return parsed.version;
    }

    /** New: read full mod identity (id, name, version). */
    public static ModInfo readModInfo(Path jarPath) {
        try (ZipFile zf = new ZipFile(jarPath.toFile())) {
            ModInfo info;
            info = tryFabricInfo(zf); if (isComplete(info)) return info; if (info != null) return info;
            info = tryQuiltInfo(zf); if (isComplete(info)) return info; if (info != null) return info;
            info = tryNeoForgeJsonInfo(zf); if (isComplete(info)) return info; if (info != null) return info;
            info = tryForgeTomlInfo(zf); if (isComplete(info)) return info; if (info != null) return info;
        } catch (IOException ignored) {}
        return null;
    }

    public static final class ModInfo {
        public final String id;
        public final String name;
        public final String version;

        public ModInfo(String id, String name, String version) {
            this.id = emptyToNull(id);
            this.name = emptyToNull(name);
            this.version = emptyToNull(version);
        }

        private static String emptyToNull(String s) {
            return (s == null || s.isBlank()) ? null : s;
        }
    }

    private static boolean isComplete(ModInfo mi) { return mi != null && mi.id != null && mi.version != null; }

    // Fabric parsing returning ModInfo
    private static ModInfo tryFabricInfo(ZipFile zf) throws IOException {
        ZipEntry e = zf.getEntry("fabric.mod.json");
        if (e == null) return null;
        try (InputStream is = zf.getInputStream(e)) {
            JsonElement el = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            if (!el.isJsonObject()) return null;
            JsonObject obj = el.getAsJsonObject();
            return new ModInfo(asString(obj.get("id")), asString(obj.get("name")), asString(obj.get("version")));
        }
    }

    private static ModInfo tryQuiltInfo(ZipFile zf) throws IOException {
        ZipEntry e = zf.getEntry("quilt.mod.json");
        if (e == null) return null;
        try (InputStream is = zf.getInputStream(e)) {
            JsonElement el = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            if (!el.isJsonObject()) return null;
            JsonObject obj = el.getAsJsonObject();
            String id = asString(obj.get("id"));
            String name = asString(obj.get("name"));
            String version = null;
            JsonElement ql = obj.get("quilt_loader");
            if (ql != null && ql.isJsonObject()) {
                JsonObject qlo = ql.getAsJsonObject();
                if (id == null) id = asString(qlo.get("id"));
                if (name == null) name = asString(qlo.get("name"));
                if (version == null) version = asString(qlo.get("version"));
            }
            if (version == null) version = asString(obj.get("version"));
            return new ModInfo(id, name, version);
        }
    }

    private static ModInfo tryNeoForgeJsonInfo(ZipFile zf) throws IOException {
        ZipEntry e = zf.getEntry("neoforge.mods.json");
        if (e == null) return null;
        try (InputStream is = zf.getInputStream(e)) {
            JsonElement el = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            if (!el.isJsonObject()) return null;
            JsonObject obj = el.getAsJsonObject();
            String id = null, name = null, version = null;
            JsonElement modsEl = obj.get("mods");
            if (modsEl != null && modsEl.isJsonArray() && modsEl.getAsJsonArray().size() > 0) {
                JsonElement first = modsEl.getAsJsonArray().get(0);
                if (first.isJsonObject()) {
                    JsonObject fo = first.getAsJsonObject();
                    id = asString(fo.get("modId"));
                    if (id == null) id = asString(fo.get("id"));
                    name = asString(fo.get("displayName"));
                    if (name == null) name = asString(fo.get("name"));
                    version = asString(fo.get("version"));
                }
            }
            if (version == null) version = asString(obj.get("version"));
            return new ModInfo(id, name, version);
        }
    }

    private static ModInfo tryForgeTomlInfo(ZipFile zf) throws IOException {
        ParsedForgeModsToml p = parseForgeModsToml(zf);
        return p.isEmpty() ? null : new ModInfo(p.modId, p.displayName, p.version);
    }

    // Helper data holder
    private static final class ParsedForgeModsToml {
        String modId;
        String displayName;
        String version;

        boolean isEmpty() { return modId == null && displayName == null && version == null; }
    }

    /**
     * Parse the first [mods] or [[mods]] table in mods.toml capturing modId, displayName, version.
     * Handles stripping inline comments (# ...) outside of quoted strings and trims values.
     */
    private static ParsedForgeModsToml parseForgeModsToml(ZipFile zf) throws IOException {
        ZipEntry e = zf.getEntry("META-INF/mods.toml");
        ParsedForgeModsToml r = new ParsedForgeModsToml();
        if (e == null) return r;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(zf.getInputStream(e), StandardCharsets.UTF_8))) {
            boolean inMods = false;
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[")) {
                    // Section header
                    if (inMods && !trimmed.equals("[mods]") && !trimmed.equals("[[mods]]")) {
                        // leaving mods section
                        break;
                    }
                    if (!inMods && (trimmed.equals("[mods]") || trimmed.equals("[[mods]]"))) {
                        inMods = true; // enter mods table
                    }
                    continue; // proceed to next line
                }
                if (!inMods) continue;
                String noComment = stripTomlComment(line);
                if (noComment.isEmpty()) continue;
                int eq = noComment.indexOf('=');
                if (eq <= 0) continue;
                String key = noComment.substring(0, eq).trim();
                String value = unquote(noComment.substring(eq + 1).trim());
                if (value != null && value.isEmpty()) value = null;
                switch (key) {
                    case "modId": if (r.modId == null) r.modId = value; break;
                    case "displayName": if (r.displayName == null) r.displayName = value; break;
                    case "version": if (r.version == null) r.version = value; break;
                    default: break;
                }
                if (r.modId != null && r.displayName != null && r.version != null) break; // all found
            }
        }
        // Clean potential trailing comment fragments (defensive)
        if (r.displayName != null) r.displayName = cleanupValue(r.displayName);
        if (r.modId != null) r.modId = cleanupValue(r.modId);
        if (r.version != null) r.version = cleanupValue(r.version);
        return r;
    }

    private static String cleanupValue(String v) {
        // Remove lingering unbalanced quotes or stray comment markers
        String trimmed = v.trim();
        // If contains an unescaped #, strip after first #
        int hashPos = indexOfUnescapedHashOutsideQuotes(trimmed);
        if (hashPos >= 0) trimmed = trimmed.substring(0, hashPos).trim();
        // Remove surrounding quotes again if any
        return unquote(trimmed);
    }

    private static int indexOfUnescapedHashOutsideQuotes(String s) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '#' && !inSingle && !inDouble) return i;
        }
        return -1;
    }

    private static String stripTomlComment(String line) {
        boolean inSingle = false, inDouble = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && !inSingle) { inDouble = !inDouble; sb.append(c); continue; }
            if (c == '\'' && !inDouble) { inSingle = !inSingle; sb.append(c); continue; }
            if (c == '#' && !inSingle && !inDouble) break; // start of comment
            sb.append(c);
        }
        return sb.toString().trim();
    }

    private static String unquote(String v) {
        if (v == null) return null;
        String s = v.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
        }
        return s.trim();
    }

    private static String asString(JsonElement el) { return el != null && el.isJsonPrimitive() ? el.getAsString() : null; }
}
