package com.naqqa.auth.exceptions.auth;

import com.naqqa.auth.config.auth.Errors;

public class AccountBlockedException extends RuntimeException {
    public AccountBlockedException() {
        super(Errors.LOGIN_ACCOUNT_LOCKED);
    }
}
