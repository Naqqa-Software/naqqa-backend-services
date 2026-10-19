package com.naqqa.commons.validation;

import com.naqqa.commons.error.ApiException;

/**
 * Small guard helpers for the web edge. Each throws an {@link ApiException} carrying the
 * {@code bad_request} code when the argument is missing or out of range, so controllers and
 * services validate inputs the same way instead of hand-rolling checks. The service layer may then
 * assume validated input, as the architecture blueprint requires.
 */
public final class Validation {

    private Validation() {
    }

    public static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " is required");
        }
        return value.trim();
    }

    public static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw ApiException.badRequest(field + " is required");
        }
        return value;
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int requireRange(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw ApiException.badRequest(field + " must be between " + min + " and " + max);
        }
        return value;
    }
}
