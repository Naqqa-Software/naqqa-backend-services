package com.naqqa.auth.exceptions.authorities;

import com.naqqa.auth.config.authorities.AuthoritiesErrors;

public class AuthorityAlreadyExistsException extends RuntimeException {
    public AuthorityAlreadyExistsException() {
        super(AuthoritiesErrors.AUTHORITY_ALREADY_EXISTS);
    }
}