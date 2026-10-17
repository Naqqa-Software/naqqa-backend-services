package com.naqqa.sso.saml.idp;

import com.naqqa.sso.SsoException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record SamlSubject(String userId, String email, Instant authnInstant, String acr, String sessionIndex,
                          Map<String, List<String>> attributes) {
    public SamlSubject {
        if (userId == null || userId.isEmpty() || authnInstant == null || sessionIndex == null || sessionIndex.isEmpty()) {
            throw new SsoException("SAML_SUBJECT_INVALID");
        }
        Map<String, List<String>> copy = new TreeMap<>();
        if (attributes != null) {
            attributes.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        }
        attributes = java.util.Collections.unmodifiableMap(copy);
    }
}
