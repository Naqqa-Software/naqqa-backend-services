package com.naqqa.commons.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Uniform error body returned by every Naqqa module. The core fields {@code status}, {@code code}
 * and {@code message} mirror the shape the analytics module already emits, so existing clients keep
 * working. {@code details} is optional structured context (for example a list of field errors) and
 * is omitted from the JSON when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String code, String message, Object details) {

    public static ApiError of(int status, String code, String message) {
        return new ApiError(status, code, message, null);
    }

    public static ApiError of(int status, String code, String message, Object details) {
        return new ApiError(status, code, message, details);
    }

    public static ApiError from(ApiException ex) {
        return new ApiError(ex.status(), ex.code().code(), ex.getMessage(), ex.details());
    }
}
