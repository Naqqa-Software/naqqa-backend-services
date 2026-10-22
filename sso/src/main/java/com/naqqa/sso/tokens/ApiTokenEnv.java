package com.naqqa.sso.tokens;

import com.naqqa.sso.SsoException;

public enum ApiTokenEnv {
    LIVE("live"),
    TEST("test");

    private final String code;

    ApiTokenEnv(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ApiTokenEnv fromCode(String code) {
        for (ApiTokenEnv e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new SsoException("token.malformed");
    }
}
