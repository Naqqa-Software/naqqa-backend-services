package com.naqqa.registry.service;

import com.naqqa.commons.error.ApiException;
import com.naqqa.commons.error.ErrorCode;

public final class RegistryErrors {

    public static final String TOKEN_MISSING = "token_missing";
    public static final String TOKEN_INVALID = "token_invalid";
    public static final String TOKEN_EXPIRED = "token_expired";
    public static final String TOKEN_REVOKED = "token_revoked";

    private RegistryErrors() {
    }

    public static ApiException tokenMissing() {
        return new ApiException(401, TOKEN_MISSING, "A registry token is required.");
    }

    public static ApiException tokenInvalid() {
        return new ApiException(401, TOKEN_INVALID, "The registry token is not valid.");
    }

    public static ApiException tokenExpired() {
        return new ApiException(401, TOKEN_EXPIRED, "The registry token has expired.");
    }

    public static ApiException tokenRevoked() {
        return new ApiException(401, TOKEN_REVOKED, "The registry token has been revoked.");
    }

    public static ApiException packageNotFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Package not found or not granted by this token.");
    }

    public static ApiException tokenNotFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Token not found.");
    }

    public static ApiException adminRequired() {
        return new ApiException(ErrorCode.UNAUTHORIZED, "Sign in to manage registry tokens.");
    }

    public static ApiException forbidden() {
        return new ApiException(ErrorCode.FORBIDDEN, "You do not have permission for this.");
    }

    public static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
