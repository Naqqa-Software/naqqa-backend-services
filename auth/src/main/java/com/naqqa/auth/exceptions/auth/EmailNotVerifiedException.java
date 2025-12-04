package com.naqqa.auth.exceptions.auth;

import com.naqqa.auth.config.auth.Errors;

public class EmailNotVerifiedException extends RuntimeException {

    private final String uuid;

    // Constructor with UUID
    public EmailNotVerifiedException(String uuid) {
        super(Errors.REG_EMAIL_NOT_VERIFIED);
        this.uuid = uuid;
    }

    // Optional: Constructor with custom message and UUID
    public EmailNotVerifiedException(String message, String uuid) {
        super(message);
        this.uuid = uuid;
    }

    public String getUuid() {
        return uuid;
    }
}
