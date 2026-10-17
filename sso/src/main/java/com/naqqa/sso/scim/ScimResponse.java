package com.naqqa.sso.scim;

import java.util.Map;

public record ScimResponse(int status, Map<String, Object> body, Map<String, String> headers) {
    public ScimResponse {
        body = body == null ? Map.of() : body;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public String header(String name) {
        return headers.get(name);
    }
}
