package com.naqqa.sso.tokens;

import com.naqqa.sso.SsoException;

public enum ApiTokenKind {
    PUBLISHABLE("pk"),
    SECRET("sk");

    private final String code;

    ApiTokenKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ApiTokenKind fromCode(String code) {
        for (ApiTokenKind k : values()) {
            if (k.code.equals(code)) {
                return k;
            }
        }
        throw new SsoException("token.malformed");
    }
}
