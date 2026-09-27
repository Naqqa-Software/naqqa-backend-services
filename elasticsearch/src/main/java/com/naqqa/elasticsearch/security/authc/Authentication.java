package com.naqqa.elasticsearch.security.authc;

public record Authentication(User authenticatedUser, User effectiveUser, String realmName, boolean runAs) {

    public static Authentication of(User user, String realmName) {
        return new Authentication(user, user, realmName, false);
    }

    public Authentication withRunAs(User target) {
        return new Authentication(authenticatedUser, target, realmName, true);
    }
}
