package com.naqqa.commons.error;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String code, String message, Object details) {

    public static ApiError of(int status, String code, String message) {
        return new ApiError(status, code, message, null);
    }

    public static ApiError of(int status, String code, String message, Object details) {
        return new ApiError(status, code, message, details);
    }

    public static ApiError from(ApiException ex) {
        return new ApiError(ex.status(), ex.code(), ex.getMessage(), ex.details());
    }
}
