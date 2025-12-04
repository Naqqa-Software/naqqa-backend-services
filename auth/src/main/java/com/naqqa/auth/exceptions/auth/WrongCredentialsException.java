package com.naqqa.auth.exceptions.auth;

import com.naqqa.auth.config.auth.Errors;

public class WrongCredentialsException extends RuntimeException {
    public WrongCredentialsException() {
        super(Errors.LOGIN_WRONG_CREDENTIALS);
    }
}
