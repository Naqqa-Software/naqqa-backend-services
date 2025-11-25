package com.naqqa.auth.exceptions.auth;

import com.naqqa.auth.config.Errors;

public class EmailNotFoundException extends RuntimeException {
    public EmailNotFoundException() {
        super(Errors.PASSWORD_RESET_EMAIL_NOT_FOUND);
    }
}
