package com.naqqa.analytics.banners.service;

import com.naqqa.analytics.banners.service.BannerImageInspector.ImageInfo;
import com.naqqa.analytics.banners.service.BannerImageInspector.Rules;
import com.naqqa.analytics.banners.service.BannerImageInspector.Violation;
import com.naqqa.analytics.banners.spi.BannerAssetStorage;
import com.naqqa.analytics.banners.web.BannerDtos.AssetDto;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public class BannerAssetService {

    private final Supplier<BannerAssetStorage> storage;
    private final Rules rules;

    public BannerAssetService(Supplier<BannerAssetStorage> storage, Rules rules) {
        this.storage = storage;
        this.rules = rules;
    }

    public AssetDto upload(byte[] bytes, String slot, String variant) {
        if (bytes == null || bytes.length == 0) {
            throw BannerException.invalid("file", "banners.creative.empty", "File is empty");
        }
        if (bytes.length > rules.maxBytes()) {
            throw new BannerException(HttpStatus.BAD_REQUEST, "banners.creative.weight", "Image exceeds " + (rules.maxBytes() / 1024) + " KB",
                    Map.of("field", "file", "maxKb", rules.maxBytes() / 1024));
        }
        ImageInfo info = BannerImageInspector.inspect(bytes);
        List<Violation> violations = BannerImageInspector.validate(info, slot, variant, rules);
        if (!violations.isEmpty()) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("field", "file");
            extra.put("violations", violations);
            throw new BannerException(HttpStatus.BAD_REQUEST, violations.get(0).code(), violations.get(0).message(), extra);
        }
        BannerAssetStorage store = storage.get();
        if (store == null || !store.available()) {
            throw new BannerException(HttpStatus.SERVICE_UNAVAILABLE, "banners.storage_unavailable", "Asset storage is not configured");
        }
        String name = "banner-" + UUID.randomUUID().toString().replace("-", "") + "." + ("jpeg".equals(info.format()) ? "jpg" : info.format());
        String url = store.store(name, info.contentType(), bytes);
        return new AssetDto(url, info.width(), info.height(), info.bytes(), info.format());
    }
}
