package com.naqqa.commons.web;

import com.naqqa.commons.error.ApiError;
import com.naqqa.commons.error.ApiException;
import com.naqqa.commons.error.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared {@link RestControllerAdvice} that renders a uniform {@link ApiError} for every module that
 * depends on naqqa-commons. It handles the typed {@link ApiException} plus the two standard Bean
 * Validation failures. It is registered at {@link Ordered#LOWEST_PRECEDENCE}, so a module that keeps
 * its own, more specific advice (for example the analytics and banner handlers scoped by
 * {@code assignableTypes}) still takes priority for its own controllers. It deliberately does not
 * install a catch-all {@code Exception} handler, so unrelated host exceptions keep their existing
 * behaviour.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        if (ex.status() >= 500) {
            log.error("naqqa api error {} {}", ex.code().code(), ex.getMessage(), ex);
        }
        return ResponseEntity.status(ex.status()).body(ApiError.from(ex));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException ex) {
        List<String> details = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.add(fieldError.getField() + ": " + fieldError.getDefaultMessage());
        }
        ApiError body = ApiError.of(ErrorCode.VALIDATION_FAILED.status(), ErrorCode.VALIDATION_FAILED.code(),
                "Request validation failed", details);
        return ResponseEntity.status(body.status()).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        ApiError body = ApiError.of(ErrorCode.VALIDATION_FAILED.status(), ErrorCode.VALIDATION_FAILED.code(),
                ex.getMessage());
        return ResponseEntity.status(body.status()).body(body);
    }
}
