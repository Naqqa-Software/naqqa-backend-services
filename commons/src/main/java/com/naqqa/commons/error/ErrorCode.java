package com.naqqa.commons.error;

/**
 * Canonical catalogue of API error codes shared by every Naqqa backend module. The string
 * {@link #code()} is the stable wire value returned in {@link ApiError#code()}, and
 * {@link #status()} is the default HTTP status a module uses when it does not override it.
 * The codes follow the lowercase_snake convention the analytics module already emits
 * (for example {@code bad_request}, {@code forbidden}, {@code not_found}) so existing clients
 * keep working when a module adopts this enum.
 */
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

    /** The stable lowercase_snake wire value. */
    public String code() {
        return code;
    }

    /** The default HTTP status for this code. */
    public int status() {
        return status;
    }
}
