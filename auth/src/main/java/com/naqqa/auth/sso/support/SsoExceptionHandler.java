package com.naqqa.auth.sso.support;

import com.naqqa.sso.SsoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SsoExceptionHandler {

    @ExceptionHandler(SsoApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(SsoApiException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.getStatus());
        if (ex.getRetryAfterSeconds() > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()));
        }
        if ("STEP_UP_REQUIRED".equals(ex.getCode())) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_user_authentication\"");
        }
        return builder.body(SsoErrorBody.of(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(SsoException.class)
    public ResponseEntity<Map<String, Object>> handleCore(SsoException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(SsoErrorBody.of(SsoErrorBody.codeOf(ex.code()), "Request rejected"));
    }
}
