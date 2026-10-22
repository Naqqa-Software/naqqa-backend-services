package com.naqqa.authenticator.service;

import com.naqqa.commons.error.ApiException;

public final class AuthenticatorErrors {

    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String SETUP_EXPIRED = "SETUP_EXPIRED";
    public static final String OTP_INVALID = "OTP_INVALID";
    public static final String TOTP_ALREADY_ENROLLED = "TOTP_ALREADY_ENROLLED";
    public static final String MFA_TOKEN_EXPIRED = "MFA_TOKEN_EXPIRED";
    public static final String METHOD_UNAVAILABLE = "METHOD_UNAVAILABLE";
    public static final String RATE_LIMITED = "RATE_LIMITED";

    private AuthenticatorErrors() {
    }

    public static ApiException invalidRequest(String message) {
        return new ApiException(400, INVALID_REQUEST, message);
    }

    public static ApiException unauthorized() {
        return new ApiException(401, UNAUTHORIZED, "Sign in to continue.");
    }

    public static ApiException notFound() {
        return new ApiException(404, NOT_FOUND, "Not found.");
    }

    public static ApiException notConfigured() {
        return new ApiException(503, NOT_CONFIGURED, "This feature is not enabled on the server.");
    }

    public static ApiException setupExpired() {
        return new ApiException(400, SETUP_EXPIRED, "The setup expired. Start again.");
    }

    public static ApiException otpInvalid() {
        return new ApiException(400, OTP_INVALID, "That code is not right. Try again.");
    }

    public static ApiException alreadyEnrolled() {
        return new ApiException(409, TOTP_ALREADY_ENROLLED, "An authenticator app is already set up.");
    }

    public static ApiException tokenExpired() {
        return new ApiException(401, MFA_TOKEN_EXPIRED, "The sign in step expired. Sign in again.");
    }

    public static ApiException methodUnavailable() {
        return new ApiException(400, METHOD_UNAVAILABLE, "This verification method is not available.");
    }

    public static ApiException rateLimited() {
        return new ApiException(429, RATE_LIMITED, "Too many attempts. Sign in again.");
    }
}
