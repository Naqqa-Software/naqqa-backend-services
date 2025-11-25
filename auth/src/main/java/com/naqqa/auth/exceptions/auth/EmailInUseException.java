package com.naqqa.auth.exceptions.auth;

import com.naqqa.auth.config.Errors;

public class EmailInUseException extends RuntimeException {
    public EmailInUseException() {
        super(Errors.REG_EMAIL_ALREADY_EXISTS);
    }
}

