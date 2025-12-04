package com.naqqa.auth.exceptions.authorities;

import com.naqqa.auth.config.authorities.AuthoritiesErrors;

public class InvalidRoleException extends RuntimeException {
    public InvalidRoleException() {
        super(AuthoritiesErrors.ROLE_INVALID_NAME);
    }
}
