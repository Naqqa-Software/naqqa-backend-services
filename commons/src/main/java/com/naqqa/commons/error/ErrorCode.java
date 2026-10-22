package com.naqqa.commons.error;

public enum ErrorCode {

    BAD_REQUEST("bad_request", 400),
    UNAUTHORIZED("unauthorized", 401),
    FORBIDDEN("forbidden", 403),
    NOT_FOUND("not_found", 404),
    CONFLICT("conflict", 409),
    VALIDATION_FAILED("validation_failed", 422),
    RANGE_TOO_LARGE("range_too_large", 422),
    RATE_LIMITED("rate_limited", 429),
    INTERNAL("internal_error", 500),
    UNAVAILABLE("unavailable", 503);

    private final String code;
    private final int status;

    ErrorCode(String code, int status) {
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
