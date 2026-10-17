package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public final class Scopes {
    public static final String OPENID = "openid";
    public static final String OFFLINE_ACCESS = "offline_access";
    public static final int MAX_SCOPES = 50;

    private Scopes() {
    }

    public static void requireToken(String s) {
        if (s == null || s.isEmpty() || s.length() > 128) {
            throw new SsoException("oidc.scope_invalid");
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c == '"' || c == '\\' || c > 0x7E) {
                throw new SsoException("oidc.scope_invalid");
            }
        }
    }

    public static Set<String> parse(String value) {
        Set<String> out = new LinkedHashSet<>();
        if (value == null || value.isEmpty()) {
            return out;
        }
        for (String part : value.split(" ", -1)) {
            if (part.isEmpty()) {
                throw new SsoException("oidc.scope_invalid");
            }
            requireToken(part);
            out.add(part);
            if (out.size() > MAX_SCOPES) {
                throw new SsoException("oidc.scope_invalid");
            }
        }
        return out;
    }

    public static String join(Collection<String> scopes) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (scopes.contains(OPENID)) {
            out.add(OPENID);
        }
        for (String s : new java.util.TreeSet<>(scopes)) {
            if (!s.equals(OPENID)) {
                out.add(s);
            }
        }
        return String.join(" ", out);
    }
}
