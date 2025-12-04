package com.naqqa.auth.exceptions.authorities;

import com.naqqa.auth.config.authorities.AuthoritiesErrors;

public class NoAuthoritiesException extends RuntimeException {
    public NoAuthoritiesException() {
        super(AuthoritiesErrors.ROLE_NO_AUTHORITIES);
    }
}
