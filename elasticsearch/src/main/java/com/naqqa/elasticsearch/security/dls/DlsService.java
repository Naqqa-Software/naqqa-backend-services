package com.naqqa.elasticsearch.security.dls;

import com.naqqa.elasticsearch.security.authz.IndicesPrivileges;
import com.naqqa.elasticsearch.security.authz.RoleDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DlsService {

    public Optional<Map<String, Object>> resolveFilter(List<RoleDescriptor> roles, String index) {
        List<Map<String, Object>> queries = new ArrayList<>();
        boolean matchedAny = false;
        for (RoleDescriptor role : roles) {
            for (IndicesPrivileges privileges : role.indicesPrivileges()) {
                if (!privileges.matchesIndex(index)) {
                    continue;
                }
                matchedAny = true;
                if (privileges.query() == null) {
                    return Optional.empty();
                }
                queries.add(privileges.query());
            }
        }
        if (!matchedAny || queries.isEmpty()) {
            return Optional.empty();
        }
        if (queries.size() == 1) {
            return Optional.of(queries.get(0));
        }
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("should", queries);
        inner.put("minimum_should_match", 1);
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("bool", inner);
        return Optional.of(bool);
    }
}
