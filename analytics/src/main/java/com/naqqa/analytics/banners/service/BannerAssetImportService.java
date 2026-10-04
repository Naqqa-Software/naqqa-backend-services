package com.naqqa.analytics.banners.service;

import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.model.BannerImage;
import com.naqqa.analytics.banners.service.BannerImageInspector.ImageInfo;
import com.naqqa.analytics.banners.spi.BannerAssetStorage;
import com.naqqa.analytics.banners.store.BannerCampaignCache;
import com.naqqa.analytics.banners.store.BannerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
public class BannerAssetImportService {

    public record ImportResult(int scanned, int imported, int skipped, List<Map<String, String>> failed, boolean dryRun) {
    }

    private final BannerRepository repository;
    private final BannerCampaignCache cache;
    private final Supplier<BannerAssetStorage> storage;
    private final long maxBytes;
    private final HttpClient http;

    public BannerAssetImportService(BannerRepository repository, BannerCampaignCache cache, Supplier<BannerAssetStorage> storage, long maxBytes) {
        this.repository = repository;
        this.cache = cache;
        this.storage = storage;
        this.maxBytes = Math.max(1024, maxBytes);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public ImportResult importRelative(String base, String prefix, boolean dryRun) {
        String origin = origin(base);
        String pathPrefix = prefix == null || prefix.isBlank() ? "/assets/" : prefix.trim();
        if (!pathPrefix.startsWith("/") || pathPrefix.startsWith("//")) {
            throw BannerException.invalid("prefix", "banners.validation.invalid_url", "Prefix must be a relative path");
        }
        BannerAssetStorage store = storage.get();
        if (!dryRun && (store == null || !store.available())) {
            throw new BannerException(HttpStatus.SERVICE_UNAVAILABLE, "banners.storage_unavailable", "Asset storage is not configured");
        }
        Map<String, BannerImage> uploaded = new HashMap<>();
        List<Map<String, String>> failed = new ArrayList<>();
        int scanned = 0;
        int imported = 0;
        int skipped = 0;
        for (BannerCreative creative : repository.allCreatives()) {
            boolean changed = false;
            for (String variant : List.of("desktop", "mobile")) {
                BannerImage image = "desktop".equals(variant) ? creative.getDesktop() : creative.getMobile();
                if (image == null || image.getUrl() == null || !image.getUrl().startsWith(pathPrefix)) {
                    continue;
                }
                scanned++;
                String path = image.getUrl();
                if (dryRun) {
                    skipped++;
                    continue;
                }
                try {
                    BannerImage stored = uploaded.get(path);
                    if (stored == null) {
                        stored = upload(store, origin + path);
                        uploaded.put(path, stored);
                    }
                    BannerImage copy = new BannerImage(stored.getUrl(), stored.getW(), stored.getH(), stored.getBytes(), stored.getFormat());
                    if ("desktop".equals(variant)) {
                        creative.setDesktop(copy);
                    } else {
                        creative.setMobile(copy);
                    }
                    changed = true;
                    imported++;
                } catch (Exception e) {
                    Map<String, String> f = new LinkedHashMap<>();
                    f.put("creativeId", creative.getId());
                    f.put("url", path);
                    f.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    failed.add(f);
                    log.warn("Banner asset {} could not be imported: {}", path, e.getMessage());
                }
            }
            if (changed) {
                repository.saveCreative(creative);
            }
        }
        if (imported > 0) {
            cache.invalidate();
        }
        return new ImportResult(scanned, imported, skipped, failed, dryRun);
    }

    private BannerImage upload(BannerAssetStorage store, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        byte[] bytes;
        try (InputStream in = response.body()) {
            bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
        }
        if (bytes.length > maxBytes) {
            throw new IllegalStateException("File exceeds " + (maxBytes / 1024) + " KB");
        }
        ImageInfo info = BannerImageInspector.inspect(bytes);
        if (info == null || info.width() <= 0 || info.height() <= 0) {
            throw new IllegalStateException("Not a supported image");
        }
        String name = "banner-" + UUID.randomUUID().toString().replace("-", "") + "." + ("jpeg".equals(info.format()) ? "jpg" : info.format());
        String stored = store.store(name, info.contentType(), bytes);
        return new BannerImage(stored, info.width(), info.height(), info.bytes(), info.format());
    }

    private static String origin(String base) {
        if (base == null || base.isBlank()) {
            throw BannerException.invalid("base", "banners.validation.required", "Base URL is required");
        }
        try {
            URI uri = new URI(base.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw BannerException.invalid("base", "banners.validation.invalid_url", "Base must be an http(s) origin");
            }
            return scheme + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (BannerException e) {
            throw e;
        } catch (Exception e) {
            throw BannerException.invalid("base", "banners.validation.invalid_url", "Base must be an http(s) origin");
        }
    }
}
