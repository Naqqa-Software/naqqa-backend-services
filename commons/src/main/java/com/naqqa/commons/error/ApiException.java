package com.naqqa.commons.error;

/**
 * Typed domain exception the service layer throws; {@link com.naqqa.commons.web.ApiExceptionHandler}
 * renders it as an {@link ApiError}. It carries an HTTP {@code status}, a stable {@link ErrorCode}
 * and optional structured {@code details}. The static factories cover the common cases so modules
 * do not re-declare their own exception hierarchy.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final ErrorCode code;
    private final transient Object details;

    public ApiException(ErrorCode code, String message) {
        this(code.status(), code, message, null);
    }

    public ApiException(int status, ErrorCode code, String message) {
        this(status, code, message, null);
    }

    public ApiException(int status, ErrorCode code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(ErrorCode.BAD_REQUEST, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(ErrorCode.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ErrorCode.FORBIDDEN, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(ErrorCode.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    public int status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }

    public Object details() {
        return details;
    }
}
