package com.naqqa.sso.oidc;

import java.util.Set;

public final class GrantTypes {
    public static final String AUTHORIZATION_CODE = "authorization_code";
    public static final String REFRESH_TOKEN = "refresh_token";
    public static final String CLIENT_CREDENTIALS = "client_credentials";
    public static final String DEVICE_CODE = "urn:ietf:params:oauth:grant-type:device_code";
    public static final Set<String> SUPPORTED = Set.of(AUTHORIZATION_CODE, REFRESH_TOKEN, CLIENT_CREDENTIALS, DEVICE_CODE);

    private GrantTypes() {
    }
}
