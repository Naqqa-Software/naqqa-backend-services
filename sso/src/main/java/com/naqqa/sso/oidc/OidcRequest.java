package com.naqqa.sso.oidc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record OidcRequest(String method, Map<String, List<String>> params, Map<String, String> headers, String sessionToken,
                          String deviceId, String ip, String tenantId) {
    public static final int MAX_PARAMS = 64;
    public static final int MAX_PARAM_LENGTH = 8192;

    public OidcRequest {
        method = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        Map<String, List<String>> p = new LinkedHashMap<>();
        if (params != null) {
            for (Map.Entry<String, List<String>> e : params.entrySet()) {
                p.put(e.getKey(), List.copyOf(e.getValue()));
            }
        }
        params = java.util.Collections.unmodifiableMap(p);
        Map<String, String> h = new LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                h.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        headers = java.util.Collections.unmodifiableMap(h);
    }

    public static OidcRequest of(String method, Map<String, String> params) {
        Map<String, List<String>> p = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            p.put(e.getKey(), List.of(e.getValue()));
        }
        return new OidcRequest(method, p, Map.of(), null, null, null, null);
    }

    public OidcRequest withHeader(String name, String value) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(name.toLowerCase(Locale.ROOT), value);
        return new OidcRequest(method, params, h, sessionToken, deviceId, ip, tenantId);
    }

    public OidcRequest withSession(String token) {
        return new OidcRequest(method, params, headers, token, deviceId, ip, tenantId);
    }

    public OidcRequest withIp(String value) {
        return new OidcRequest(method, params, headers, sessionToken, deviceId, value, tenantId);
    }

    public OidcRequest withTenant(String value) {
        return new OidcRequest(method, params, headers, sessionToken, deviceId, ip, value);
    }

    public OidcRequest withParam(String name, String value) {
        Map<String, List<String>> p = new LinkedHashMap<>(params);
        if (value == null) {
            p.remove(name);
        } else {
            p.put(name, List.of(value));
        }
        return new OidcRequest(method, p, headers, sessionToken, deviceId, ip, tenantId);
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean has(String name) {
        return params.containsKey(name);
    }

    public String param(String name) {
        List<String> v = params.get(name);
        if (v == null || v.isEmpty()) {
            return null;
        }
        if (v.size() > 1) {
            throw OidcError.invalidRequest("oidc.param_repeated", "parameter repeated: " + name);
        }
        String s = v.get(0);
        if (s.length() > MAX_PARAM_LENGTH) {
            throw OidcError.invalidRequest("oidc.param_too_long", "parameter too long: " + name);
        }
        return s.isEmpty() ? null : s;
    }

    public void checkLimits() {
        if (params.size() > MAX_PARAMS) {
            throw OidcError.invalidRequest("oidc.too_many_params", "too many parameters");
        }
    }
}
