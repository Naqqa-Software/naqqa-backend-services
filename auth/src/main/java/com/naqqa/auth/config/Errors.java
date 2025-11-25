package com.naqqa.auth.config;

public class Errors {

    // ----------------------------
    // Registration & Email Verify
    // ----------------------------
    public static final String REG_EMAIL_ALREADY_EXISTS = "Auth.Errors.Register.EmailAlreadyExists";
    public static final String REG_EMAIL_NOT_VERIFIED = "Auth.Errors.Register.EmailNotVerified";
    public static final String REG_INVALID_EMAIL = "Auth.Errors.Register.InvalidEmail";
    public static final String REG_PASSWORD_WEAK = "Auth.Errors.Register.PasswordWeak";
    public static final String REG_CODE_EXPIRED = "Auth.Errors.Register.CodeExpired";
    public static final String REG_CODE_INVALID = "Auth.Errors.Register.InvalidCode";
    public static final String REG_PENDING_NOT_FOUND = "Auth.Errors.Register.PendingNotFound";
    public static final String REG_TOO_MANY_REQUESTS = "Auth.Errors.Register.TooManyRequests";

    // ----------------------------
    // Login
    // ----------------------------
    public static final String LOGIN_EMAIL_NOT_FOUND = "Auth.Errors.Login.EmailNotFound";
    public static final String LOGIN_WRONG_CREDENTIALS = "Auth.Errors.Login.WrongCredentials";
    public static final String LOGIN_ACCOUNT_LOCKED = "Auth.Errors.Login.AccountLocked";
    public static final String LOGIN_NOT_VERIFIED = "Auth.Errors.Login.EmailNotVerified";
    public static final String LOGIN_TOO_MANY_ATTEMPTS = "Auth.Errors.Login.TooManyAttempts";

    // ----------------------------
    // Password Reset
    // ----------------------------
    public static final String PASSWORD_RESET_SAME = "Auth.Errors.PasswordReset.SamePassword";
    public static final String PASSWORD_RESET_EXPIRED_LINK = "Auth.Errors.PasswordReset.ExpiredLink";
    public static final String PASSWORD_RESET_INVALID_REQUEST = "Auth.Errors.PasswordReset.InvalidRequest";
    public static final String PASSWORD_RESET_INCORRECT_PASSWORD = "Auth.Errors.PasswordReset.IncorrectPassword";
    public static final String PASSWORD_RESET_EMAIL_NOT_FOUND = "Auth.Errors.PasswordReset.EmailNotFound";
    public static final String PASSWORD_RESET_TOO_MANY_REQUESTS = "Auth.Errors.PasswordReset.TooManyRequests";

    // ----------------------------
    // Token / Session / JWT
    // ----------------------------
    public static final String TOKEN_INVALID = "Auth.Errors.Token.Invalid";
    public static final String TOKEN_EXPIRED = "Auth.Errors.Token.Expired";
    public static final String TOKEN_SIGNATURE_INVALID = "Auth.Errors.Token.SignatureInvalid";
    public static final String TOKEN_MISSING = "Auth.Errors.Token.Missing";

    // ----------------------------
    // Permissions & Roles
    // ----------------------------
    public static final String AUTH_UNAUTHORIZED = "Auth.Errors.Unauthorized";
    public static final String AUTH_FORBIDDEN = "Auth.Errors.Forbidden";
    public static final String AUTH_INSUFFICIENT_PERMISSIONS = "Auth.Errors.InsufficientPermissions";

    // ----------------------------
    // Common Validation
    // ----------------------------
    public static final String INVALID_REQUEST = "Auth.Errors.InvalidRequest";
    public static final String INVALID_PAYLOAD = "Auth.Errors.InvalidPayload";
    public static final String MISSING_FIELDS = "Auth.Errors.MissingFields";

    // ----------------------------
    // System / Internal
    // ----------------------------
    public static final String INTERNAL_ERROR = "Auth.Errors.Internal";
    public static final String SERVICE_UNAVAILABLE = "Auth.Errors.ServiceUnavailable";
    public static final String EMAIL_SEND_FAILED = "Auth.Errors.EmailSendFailed";
}
