package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;

public final class OidcError extends SsoException {
    private static final long serialVersionUID = 1L;
    public static final String INVALID_REQUEST = "invalid_request";
    public static final String INVALID_CLIENT = "invalid_client";
    public static final String INVALID_GRANT = "invalid_grant";
    public static final String UNAUTHORIZED_CLIENT = "unauthorized_client";
    public static final String UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type";
    public static final String UNSUPPORTED_RESPONSE_TYPE = "unsupported_response_type";
    public static final String INVALID_SCOPE = "invalid_scope";
    public static final String ACCESS_DENIED = "access_denied";
    public static final String SERVER_ERROR = "server_error";
    public static final String LOGIN_REQUIRED = "login_required";
    public static final String CONSENT_REQUIRED = "consent_required";
    public static final String INTERACTION_REQUIRED = "interaction_required";
    public static final String ACCOUNT_SELECTION_REQUIRED = "account_selection_required";
    public static final String REQUEST_NOT_SUPPORTED = "request_not_supported";
    public static final String REQUEST_URI_NOT_SUPPORTED = "request_uri_not_supported";
    public static final String INVALID_REQUEST_URI = "invalid_request_uri";
    public static final String INVALID_TOKEN = "invalid_token";
    public static final String INSUFFICIENT_SCOPE = "insufficient_scope";
    public static final String AUTHORIZATION_PENDING = "authorization_pending";
    public static final String SLOW_DOWN = "slow_down";
    public static final String EXPIRED_TOKEN = "expired_token";
    public static final String INVALID_DPOP_PROOF = "invalid_dpop_proof";
    public static final String USE_DPOP_NONCE = "use_dpop_nonce";
    public static final String INVALID_REDIRECT_URI = "invalid_redirect_uri";
    public static final String INVALID_CLIENT_METADATA = "invalid_client_metadata";
    public static final String TEMPORARILY_UNAVAILABLE = "temporarily_unavailable";

    private final String error;
    private final String description;

    public OidcError(String code, String error, String description) {
        super(code);
        this.error = error;
        this.description = description;
    }

    public static OidcError of(String code, String error, String description) {
        return new OidcError(code, error, description);
    }

    public static OidcError invalidRequest(String code, String description) {
        return new OidcError(code, INVALID_REQUEST, description);
    }

    public static OidcError invalidGrant(String code, String description) {
        return new OidcError(code, INVALID_GRANT, description);
    }

    public static OidcError invalidClient(String code) {
        return new OidcError(code, INVALID_CLIENT, "client authentication failed");
    }

    public String error() {
        return error;
    }

    public String description() {
        return description;
    }

    public int status() {
        switch (error) {
            case INVALID_CLIENT:
            case INVALID_TOKEN:
                return 401;
            case INSUFFICIENT_SCOPE:
                return 403;
            case SERVER_ERROR:
                return 500;
            case TEMPORARILY_UNAVAILABLE:
                return 503;
            default:
                return 400;
        }
    }
}
