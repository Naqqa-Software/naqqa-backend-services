package com.naqqa.auth.exceptions.authorities;

import com.naqqa.auth.config.authorities.AuthoritiesErrors;

public class AuthorityNotFoundException extends RuntimeException {
    public AuthorityNotFoundException() {
        super(AuthoritiesErrors.AUTHORITY_NOT_FOUND);
    }
}