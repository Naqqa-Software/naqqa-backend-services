package com.naqqa.elasticsearch.security.authz;

import com.naqqa.elasticsearch.security.support.WildcardMatcher;

import java.util.Map;
import java.util.Set;

public record IndicesPrivileges(java.util.List<String> indices, Set<IndexPrivilege> privileges,
                                 Map<String, Object> query, FieldSecurity fieldSecurity) {

    public boolean matchesIndex(String index) {
        return WildcardMatcher.matchesAny(indices, index);
    }

    public boolean grants(String action) {
        return privileges != null && privileges.stream().anyMatch(p -> p.implies(action));
    }
}
