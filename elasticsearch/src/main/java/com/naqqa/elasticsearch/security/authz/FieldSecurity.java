package com.naqqa.elasticsearch.security.authz;

import com.naqqa.elasticsearch.security.support.WildcardMatcher;

import java.util.List;

public record FieldSecurity(List<String> grant, List<String> except) {

    public static final FieldSecurity ALL = new FieldSecurity(List.of("*"), List.of());

    public boolean isGranted(String field) {
        boolean granted = grant == null || grant.isEmpty() || WildcardMatcher.matchesAny(grant, field);
        if (!granted) {
            return false;
        }
        if (except != null && WildcardMatcher.matchesAny(except, field)) {
            return false;
        }
        return true;
    }
}
