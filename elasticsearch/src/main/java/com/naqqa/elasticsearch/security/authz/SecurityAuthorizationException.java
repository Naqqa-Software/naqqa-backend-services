package com.naqqa.elasticsearch.security.authz;

public class SecurityAuthorizationException extends RuntimeException {

    public SecurityAuthorizationException(String message) {
        super(message);
    }
}
