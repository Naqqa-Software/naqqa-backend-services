package com.naqqa.auth.exceptions.auth;

import com.naqqa.auth.config.Errors;

public class SamePasswordException extends RuntimeException {
    public SamePasswordException() {
        super(Errors.PASSWORD_RESET_SAME);
    }
}
