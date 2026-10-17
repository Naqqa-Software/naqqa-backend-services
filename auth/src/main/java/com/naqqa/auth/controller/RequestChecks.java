package com.naqqa.auth.controller;

import com.naqqa.auth.config.auth.Errors;
import com.naqqa.auth.exceptions.BadRequestException;

final class RequestChecks {

    private RequestChecks() {
    }

    static <T> T body(T request) {
        if (request == null) {
            throw new BadRequestException(Errors.MISSING_FIELDS);
        }
        return request;
    }

    static void present(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new BadRequestException(Errors.MISSING_FIELDS);
            }
        }
    }
}
