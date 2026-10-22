package com.naqqa.sso.oidc;

import com.naqqa.sso.json.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record OidcResponse(Kind kind, int status, Map<String, String> headers, Map<String, Object> body, String redirectUrl,
                           Interaction interaction, List<BackchannelLogout> backchannelLogouts) {
    public enum Kind {
        JSON, REDIRECT, ERROR_PAGE, INTERACTION, EMPTY
    }

    public OidcResponse {
        headers = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(headers == null ? Map.of() : headers));
        body = body == null ? null : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(body));
        backchannelLogouts = List.copyOf(backchannelLogouts == null ? List.of() : backchannelLogouts);
    }

    static Map<String, String> noStore() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Cache-Control", "no-store");
        h.put("Pragma", "no-cache");
        return h;
    }

    public static OidcResponse json(int status, Map<String, Object> body) {
        Map<String, String> h = noStore();
        h.put("Content-Type", "application/json");
        return new OidcResponse(Kind.JSON, status, h, body, null, null, null);
    }

    public static OidcResponse json(int status, Map<String, Object> body, Map<String, String> extraHeaders) {
        Map<String, String> h = noStore();
        h.put("Content-Type", "application/json");
        h.putAll(extraHeaders);
        return new OidcResponse(Kind.JSON, status, h, body, null, null, null);
    }

    public static OidcResponse publicJson(Map<String, Object> body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Cache-Control", "public, max-age=300");
        return new OidcResponse(Kind.JSON, 200, h, body, null, null, null);
    }

    public static OidcResponse redirect(String url) {
        return new OidcResponse(Kind.REDIRECT, 302, noStore(), null, url, null, null);
    }

    public static OidcResponse redirect(String url, List<BackchannelLogout> logouts) {
        return new OidcResponse(Kind.REDIRECT, 302, noStore(), null, url, null, logouts);
    }

    public static OidcResponse errorPage(OidcError e) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("error", e.error());
        b.put("error_description", e.description());
        return new OidcResponse(Kind.ERROR_PAGE, e.status() == 401 ? 400 : e.status(), noStore(), b, null, null, null);
    }

    public static OidcResponse interaction(Interaction i) {
        return new OidcResponse(Kind.INTERACTION, 200, noStore(), null, null, i, null);
    }

    public static OidcResponse empty(int status) {
        return new OidcResponse(Kind.EMPTY, status, noStore(), null, null, null, null);
    }

    public static OidcResponse empty(int status, List<BackchannelLogout> logouts) {
        return new OidcResponse(Kind.EMPTY, status, noStore(), null, null, null, logouts);
    }

    public static OidcResponse error(OidcError e) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("error", e.error());
        if (e.description() != null) {
            b.put("error_description", e.description());
        }
        return json(e.status(), b);
    }

    public String bodyJson() {
        return body == null ? null : Json.write(body);
    }

    public String error() {
        return body == null ? null : (String) body.get("error");
    }

    public OidcResponse withHeader(String name, String value) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(name, value);
        return new OidcResponse(kind, status, h, body, redirectUrl, interaction, backchannelLogouts);
    }
}
