package com.naqqa.sso.oauth;

import com.naqqa.sso.jose.JwtClaims;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.json.JsonLimits;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OidcIdentityMapper {
    private static final List<String> CLAIM_KEYS = List.of("iss", "sub", "email", "email_verified", "name", "given_name",
            "family_name", "picture", "locale", "hd", "tid", "preferred_username", "is_private_email", "xms_edov");
    private static final JsonLimits USER_LIMITS = new JsonLimits(6, 4096);

    private OidcIdentityMapper() {
    }

    static ExternalIdentity map(ProviderConfig config, JwtClaims claims, String appleUserField) {
        String email = Sanitize.email(claims.get("email"));
        boolean verified = email != null && Sanitize.truthy(claims.get("email_verified"));
        if (config.kind() == ProviderKind.MICROSOFT && email != null && Sanitize.truthy(claims.get("xms_edov"))) {
            verified = true;
        }
        String name = Sanitize.text(claims.get("name"), 200);
        if (name == null) {
            name = joinName(Sanitize.text(claims.get("given_name"), 100), Sanitize.text(claims.get("family_name"), 100));
        }
        if (name == null && config.kind() == ProviderKind.APPLE) {
            name = appleName(appleUserField);
        }
        Map<String, Object> subset = new LinkedHashMap<>();
        for (String k : CLAIM_KEYS) {
            Object v = claims.get(k);
            if (v instanceof String) {
                String s = Sanitize.text(v, 512);
                if (s != null) {
                    subset.put(k, s);
                }
            } else if (v instanceof Boolean) {
                subset.put(k, v);
            }
        }
        return new ExternalIdentity(config.id(), claims.subject(), email, verified, name,
                Sanitize.pictureUrl(claims.get("picture")), subset);
    }

    static String appleName(String userField) {
        if (userField == null || userField.length() > 4096) {
            return null;
        }
        try {
            Map<String, Object> user = Json.parseObject(userField, USER_LIMITS);
            Object n = user.get("name");
            if (n instanceof Map) {
                Map<String, Object> nm = Json.asObject(n);
                return joinName(Sanitize.text(nm.get("firstName"), 100), Sanitize.text(nm.get("lastName"), 100));
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static String joinName(String a, String b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + " " + b;
    }
}
