package com.naqqa.auth.sso.support;

import org.springframework.http.HttpStatus;

public class SsoApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final long retryAfterSeconds;

    public SsoApiException(HttpStatus status, String code, String message) {
        this(status, code, message, 0);
    }

    public SsoApiException(HttpStatus status, String code, String message, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static SsoApiException badRequest(String code, String message) {
        return new SsoApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static SsoApiException unauthorized(String code, String message) {
        return new SsoApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static SsoApiException forbidden(String code, String message) {
        return new SsoApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static SsoApiException notFound(String code, String message) {
        return new SsoApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static SsoApiException disabled(String method) {
        return new SsoApiException(HttpStatus.NOT_FOUND, "METHOD_DISABLED", method + " is not enabled for this application");
    }

    public static SsoApiException notConfigured(String what) {
        return new SsoApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_CONFIGURED", what + " is not configured");
    }

    public static SsoApiException rateLimited(long retryAfterSeconds) {
        return new SsoApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts, retry later", Math.max(1, retryAfterSeconds));
    }

    public static SsoApiException stepUpRequired() {
        return new SsoApiException(HttpStatus.UNAUTHORIZED, "STEP_UP_REQUIRED", "Fresh authentication required");
    }
}
