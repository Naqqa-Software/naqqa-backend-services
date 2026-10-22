package com.naqqa.sso.oauth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ExternalIdentity(String provider, String subject, String email, boolean emailVerified, String name,
                               String picture, Map<String, Object> claims) {
    public ExternalIdentity {
        claims = Collections.unmodifiableMap(new LinkedHashMap<>(claims));
        if (email == null) {
            emailVerified = false;
        }
    }

    public String emailDomain() {
        if (email == null) {
            return null;
        }
        return email.substring(email.lastIndexOf('@') + 1);
    }
}
