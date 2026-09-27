package com.naqqa.elasticsearch.security.authc;

import java.util.List;

public final class RealmChain {

    private final List<Realm> realms;

    public RealmChain(List<Realm> realms) {
        this.realms = realms;
    }

    public AuthenticationResult authenticate(AuthenticationToken token) {
        for (Realm realm : realms) {
            AuthenticationResult result = realm.authenticate(token);
            if (result.status() == AuthenticationResult.Status.SUCCESS) {
                return result;
            }
            if (result.status() == AuthenticationResult.Status.TERMINATE
                    || result.status() == AuthenticationResult.Status.UNSUCCESSFUL) {
                return result;
            }
        }
        return AuthenticationResult.unsuccessful("unable to authenticate user");
    }
}
