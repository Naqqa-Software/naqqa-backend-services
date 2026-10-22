package com.naqqa.sso.saml.sp;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record SamlPrincipal(String issuer, String nameId, String nameIdFormat, String sessionIndex,
                            String authnContextClassRef, Instant authnInstant, Map<String, List<String>> attributes,
                            String assertionId, String inResponseTo, Instant expiresAt) {
    public SamlPrincipal {
        Map<String, List<String>> copy = new TreeMap<>();
        if (attributes != null) {
            attributes.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        }
        attributes = Collections.unmodifiableMap(copy);
    }

    public String firstAttribute(String name) {
        List<String> v = attributes.get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
    }
}
