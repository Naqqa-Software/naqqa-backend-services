package com.naqqa.elasticsearch.security.authc;

public final class AuthenticationResult {

    public enum Status {
        SUCCESS,
        CONTINUE,
        TERMINATE,
        UNSUCCESSFUL
    }

    private final Status status;
    private final User user;
    private final String message;
    private final String realmName;

    private AuthenticationResult(Status status, User user, String message, String realmName) {
        this.status = status;
        this.user = user;
        this.message = message;
        this.realmName = realmName;
    }

    public static AuthenticationResult success(User user, String realmName) {
        return new AuthenticationResult(Status.SUCCESS, user, null, realmName);
    }

    public static AuthenticationResult notHandled() {
        return new AuthenticationResult(Status.CONTINUE, null, null, null);
    }

    public static AuthenticationResult terminate(String message) {
        return new AuthenticationResult(Status.TERMINATE, null, message, null);
    }

    public static AuthenticationResult unsuccessful(String message) {
        return new AuthenticationResult(Status.UNSUCCESSFUL, null, message, null);
    }

    public Status status() {
        return status;
    }

    public boolean isAuthenticated() {
        return status == Status.SUCCESS;
    }

    public User user() {
        return user;
    }

    public String message() {
        return message;
    }

    public String realmName() {
        return realmName;
    }
}
