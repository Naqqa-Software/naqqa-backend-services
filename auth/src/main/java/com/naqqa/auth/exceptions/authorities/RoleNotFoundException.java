package com.naqqa.auth.exceptions.authorities;

import com.naqqa.auth.config.authorities.AuthoritiesErrors;

public class RoleNotFoundException extends RuntimeException {
    public RoleNotFoundException() {
        super(AuthoritiesErrors.ROLE_NOT_FOUND);
    }
}
