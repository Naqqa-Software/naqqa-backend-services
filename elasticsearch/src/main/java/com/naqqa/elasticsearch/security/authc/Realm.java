package com.naqqa.elasticsearch.security.authc;

public interface Realm {

    String name();

    AuthenticationResult authenticate(AuthenticationToken token);
}
