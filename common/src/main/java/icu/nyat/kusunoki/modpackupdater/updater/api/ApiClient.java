package icu.nyat.kusunoki.modpackupdater.updater.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import icu.nyat.kusunoki.modpackupdater.Constants;
import icu.nyat.kusunoki.modpackupdater.updater.Config;
import icu.nyat.kusunoki.modpackupdater.updater.dto.Manifest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class ApiClient {
    private final String baseUrl;
    private final String packId;
    private final HttpClient http;
    private final Gson gson = new GsonBuilder().create();

    public ApiClient(Config cfg) {
        this.baseUrl = cfg.getBaseUrl();
        this.packId = cfg.getPackId();
        this.http = HttpClient.newBuilder()
                .connectTimeout(cfg.getTimeout())
                .build();
    }

    private static String url(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private HttpRequest.Builder baseGet(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(120))
                .header("Accept", "application/json")
                .header("User-Agent", Constants.MOD_NAME + "/" + Constants.MOD_ID);
    }

    public Manifest getManifest() throws IOException, InterruptedException {
        String path = "/packs/" + url(packId) + "/manifest";
        Constants.LOG.info("HTTP GET {}", path);
        long start = System.nanoTime();
        HttpRequest req = baseGet(path).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        long tookMs = (System.nanoTime() - start) / 1_000_000L;
        int code = resp.statusCode();
        Constants.LOG.info("HTTP {} {} in {} ms", code, path, tookMs);
        if (code != 200) throw new IOException("Manifest failed: HTTP " + code + " body=" + resp.body());
        return gson.fromJson(resp.body(), Manifest.class);
    }

    public long downloadFileToTemp(String relativePath, Path targetFile) throws IOException, InterruptedException {
        String path = "/packs/" + url(packId) + "/file?path=" + url(relativePath);
        Constants.LOG.info("HTTP GET {}", path);
        long start = System.nanoTime();
        HttpRequest req = baseGet(path)
                .header("Accept", "application/octet-stream")
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        int code = resp.statusCode();
        if (code != 200) {
            long tookMs = (System.nanoTime() - start) / 1_000_000L;
            Constants.LOG.warn("HTTP {} {} in {} ms", code, path, tookMs);
            throw new IOException("File download failed: HTTP " + code + " for " + relativePath);
        }
        long contentLen = -1L;
        try {
            String cl = resp.headers().firstValue("Content-Length").orElse(null);
            if (cl != null) contentLen = Long.parseLong(cl);
        } catch (Exception ignore) {}
        if (contentLen >= 0) {
            Constants.LOG.info("Downloading {} ({} bytes)", relativePath, contentLen);
        } else {
            Constants.LOG.info("Downloading {} (unknown size)", relativePath);
        }
        long copied = 0L;
        Files.createDirectories(targetFile.getParent());
        try (InputStream is = resp.body(); OutputStream os = Files.newOutputStream(targetFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
                copied += n;
            }
        }
        long tookMs = (System.nanoTime() - start) / 1_000_000L;
        double speed = tookMs > 0 ? (copied / 1024.0 / 1024.0) / (tookMs / 1000.0) : 0.0;
        String speedStr = String.format(java.util.Locale.ROOT, "%.2f", speed);
        Constants.LOG.info("Downloaded {} ({} bytes) in {} ms ({} MiB/s)", relativePath, copied, tookMs, speedStr);
        return copied;
    }
}
