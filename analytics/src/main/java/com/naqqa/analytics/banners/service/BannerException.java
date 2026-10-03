package com.naqqa.analytics.banners.service;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

@Getter
public class BannerException extends RuntimeException {

    private final HttpStatus status;
    private final String errorKey;
    private final Map<String, Object> extra;

    public BannerException(HttpStatus status, String errorKey, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.errorKey = errorKey;
        this.extra = extra == null ? Map.of() : extra;
    }

    public BannerException(HttpStatus status, String errorKey, String message) {
        this(status, errorKey, message, Map.of());
    }

    public static BannerException forbidden() {
        return new BannerException(HttpStatus.FORBIDDEN, "banners.forbidden", "Access denied");
    }

    public static BannerException notFound() {
        return new BannerException(HttpStatus.NOT_FOUND, "banners.not_found", "Not found");
    }

    public static BannerException invalid(String field, String key, String message) {
        return new BannerException(HttpStatus.BAD_REQUEST, key, message, Map.of("field", field));
    }

    public static BannerException conflict(String key, String message) {
        return new BannerException(HttpStatus.CONFLICT, key, message);
    }
}
