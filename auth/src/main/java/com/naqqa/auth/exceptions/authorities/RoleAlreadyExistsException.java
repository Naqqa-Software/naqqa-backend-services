package com.naqqa.auth.exceptions.authorities;

import com.naqqa.auth.config.authorities.AuthoritiesErrors;

public class RoleAlreadyExistsException extends RuntimeException {
    public RoleAlreadyExistsException() {
        super(AuthoritiesErrors.ROLE_ALREADY_EXISTS);
    }
}
