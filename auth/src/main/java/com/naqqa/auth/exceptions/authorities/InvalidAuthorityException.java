package com.naqqa.auth.exceptions.authorities;

import com.naqqa.auth.config.authorities.AuthoritiesErrors;

public class InvalidAuthorityException extends RuntimeException {
    public InvalidAuthorityException() {
        super(AuthoritiesErrors.AUTHORITY_INVALID_NAME);
    }
}