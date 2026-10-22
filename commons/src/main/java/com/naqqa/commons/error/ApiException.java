package com.naqqa.commons.error;

public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final transient Object details;

    public ApiException(ErrorCode code, String message) {
        this(code.status(), code.code(), message, null);
    }

    public ApiException(int status, ErrorCode code, String message) {
        this(status, code.code(), message, null);
    }

    public ApiException(int status, ErrorCode code, String message, Object details) {
        this(status, code.code(), message, details);
    }

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(int status, String code, String message, Object details) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
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

    public String code() {
        return code;
    }

    public Object details() {
        return details;
    }
}
