package com.naqqa.analytics.banners.web;

import com.naqqa.analytics.banners.service.BannerException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {BannerPublicController.class, BannerAdminController.class, BannerPartnerController.class})
public class BannerExceptionHandler {

    @ExceptionHandler(BannerException.class)
    public ResponseEntity<Map<String, Object>> handle(BannerException ex) {
        return body(ex.getStatus(), ex.getErrorKey(), ex.getMessage(), ex.getExtra());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception ex) {
        return body(HttpStatus.BAD_REQUEST, "banners.bad_request", "Malformed request", Map.of());
    }

    static ResponseEntity<Map<String, Object>> body(HttpStatus status, String key, String message, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("code", key);
        body.put("errorKey", key);
        body.put("message", message);
        if (extra != null) {
            body.putAll(extra);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
