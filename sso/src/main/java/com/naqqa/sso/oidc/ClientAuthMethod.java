package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;

public enum ClientAuthMethod {
    CLIENT_SECRET_BASIC("client_secret_basic"),
    CLIENT_SECRET_POST("client_secret_post"),
    PRIVATE_KEY_JWT("private_key_jwt"),
    NONE("none");

    private final String value;

    ClientAuthMethod(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ClientAuthMethod fromValue(String value) {
        for (ClientAuthMethod m : values()) {
            if (m.value.equals(value)) {
                return m;
            }
        }
        throw new SsoException("oidc.auth_method_unsupported");
    }
}
