package com.naqqa.sso.oauth;

import java.util.Map;

public record CallbackParams(Map<String, String> values) {
    public CallbackParams {
        values = Map.copyOf(values);
    }

    public static CallbackParams of(Map<String, String> values) {
        return new CallbackParams(values);
    }

    public String get(String name) {
        return values.get(name);
    }
}
