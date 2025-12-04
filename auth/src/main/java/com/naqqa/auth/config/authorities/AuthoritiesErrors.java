package com.naqqa.auth.config.authorities;

public class AuthoritiesErrors {

    // ----------------------------
    // Roles
    // ----------------------------
    public static final String ROLE_NOT_FOUND = "Authorities.Errors.Role.NotFound";
    public static final String ROLE_ALREADY_EXISTS = "Authorities.Errors.Role.AlreadyExists";
    public static final String ROLE_INVALID_NAME = "Authorities.Errors.Role.InvalidName";
    public static final String ROLE_NO_AUTHORITIES = "Authorities.Errors.Role.NoAuthorities";
    public static final String ROLE_INVALID_AUTHORITIES = "Authorities.Errors.Role.InvalidAuthorities";
    public static final String ROLE_CANNOT_DELETE_ADMIN = "Authorities.Errors.Role.CannotDeleteAdmin";

    // ----------------------------
    // Authorities
    // ----------------------------
    public static final String AUTHORITY_NOT_FOUND = "Authorities.Errors.Authority.NotFound";
    public static final String AUTHORITY_ALREADY_EXISTS = "Authorities.Errors.Authority.AlreadyExists";
    public static final String AUTHORITY_INVALID_NAME = "Authorities.Errors.Authority.InvalidName";

    // ----------------------------
    // Permissions / Access
    // ----------------------------
    public static final String UNAUTHORIZED = "Authorities.Errors.Unauthorized";
    public static final String FORBIDDEN = "Authorities.Errors.Forbidden";
    public static final String INSUFFICIENT_PERMISSIONS = "Authorities.Errors.InsufficientPermissions";

    // ----------------------------
    // Generic / Validation
    // ----------------------------
    public static final String INVALID_REQUEST = "Authorities.Errors.InvalidRequest";
    public static final String INVALID_PAYLOAD = "Authorities.Errors.InvalidPayload";
    public static final String MISSING_FIELDS = "Authorities.Errors.MissingFields";

    // ----------------------------
    // System / Internal
    // ----------------------------
    public static final String INTERNAL_ERROR = "Authorities.Errors.Internal";
    public static final String SERVICE_UNAVAILABLE = "Authorities.Errors.ServiceUnavailable";
}
