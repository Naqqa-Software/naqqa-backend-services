package com.naqqa.analytics.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AnalyticsAdminController.class, AnalyticsPartnerController.class, AnalyticsManageController.class,
        CollectorController.class})
public class AnalyticsExceptionHandler {

    @ExceptionHandler(AnalyticsException.class)
    public ResponseEntity<Map<String, Object>> handle(AnalyticsException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ex.status());
        body.put("code", ex.code());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(ex.status()).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
