package com.naqqa.analytics.web;

public class AnalyticsException extends RuntimeException {

    public static final String BAD_REQUEST = "bad_request";
    public static final String FORBIDDEN = "forbidden";
    public static final String NOT_FOUND = "not_found";
    public static final String RANGE_TOO_LARGE = "range_too_large";
    public static final String UNAUTHORIZED = "unauthorized";

    private final int status;
    private final String code;

    public AnalyticsException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static AnalyticsException badRequest(String message) {
        return new AnalyticsException(400, BAD_REQUEST, message);
    }

    public static AnalyticsException forbidden(String message) {
        return new AnalyticsException(403, FORBIDDEN, message);
    }

    public static AnalyticsException notFound(String message) {
        return new AnalyticsException(404, NOT_FOUND, message);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
