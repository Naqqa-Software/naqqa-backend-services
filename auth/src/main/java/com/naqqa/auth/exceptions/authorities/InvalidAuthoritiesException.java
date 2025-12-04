package com.naqqa.auth.exceptions.authorities;

import com.naqqa.auth.config.authorities.AuthoritiesErrors;

public class InvalidAuthoritiesException extends RuntimeException {
    public InvalidAuthoritiesException() {
        super(AuthoritiesErrors.ROLE_INVALID_AUTHORITIES);
    }
}
