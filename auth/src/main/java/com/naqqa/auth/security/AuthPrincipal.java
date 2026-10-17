package com.naqqa.auth.security;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AuthPrincipal implements Serializable {

    private final String tokenValue;
    private final Map<String, Object> headers;
    private final Map<String, Object> claims;

    public AuthPrincipal(String tokenValue, Map<String, Object> headers, Map<String, Object> claims) {
        this.tokenValue = tokenValue;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.claims = Collections.unmodifiableMap(new LinkedHashMap<>(claims));
    }

    public String getTokenValue() {
        return tokenValue;
    }

    public Map<String, Object> getHeaders() {
        return headers;
    }

    public Map<String, Object> getClaims() {
        return claims;
    }

    public boolean hasClaim(String name) {
        return claims.get(name) != null;
    }

    @SuppressWarnings("unchecked")
    public <T> T getClaim(String name) {
        return (T) claims.get(name);
    }

    public String getClaimAsString(String name) {
        Object v = claims.get(name);
        return v == null ? null : v.toString();
    }

    public List<String> getClaimAsStringList(String name) {
        Object v = claims.get(name);
        if (v instanceof List<?> list) {
            return list.stream().filter(o -> o != null).map(Object::toString).toList();
        }
        if (v instanceof String s) {
            return List.of(s);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getClaimAsMap(String name) {
        Object v = claims.get(name);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    public Instant getClaimAsInstant(String name) {
        Object v = claims.get(name);
        return v instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null;
    }

    public String getSubject() {
        return getClaimAsString("sub");
    }

    public String getIssuer() {
        return getClaimAsString("iss");
    }

    public String getId() {
        return getClaimAsString("jti");
    }

    public Instant getIssuedAt() {
        return getClaimAsInstant("iat");
    }

    public Instant getExpiresAt() {
        return getClaimAsInstant("exp");
    }

    public Long getUserId() {
        Object v = claims.get("id");
        if (v instanceof Number n) {
            return n.longValue();
        }
        String sub = getSubject();
        try {
            return sub == null ? null : Long.valueOf(sub);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String getEmail() {
        return getClaimAsString("email");
    }

    public String getSessionId() {
        return getClaimAsString("sid");
    }

    public Instant getAuthTime() {
        Instant t = getClaimAsInstant("auth_time");
        return t != null ? t : getIssuedAt();
    }

    public List<String> getAmr() {
        List<String> amr = getClaimAsStringList("amr");
        return amr == null ? List.of() : amr;
    }

    public String getAcr() {
        return getClaimAsString("acr");
    }

    public Map<String, Object> getActor() {
        return getClaimAsMap("act");
    }

    public boolean isImpersonated() {
        return getActor() != null;
    }

    @Override
    public String toString() {
        return "AuthPrincipal[" + getSubject() + "]";
    }
}
