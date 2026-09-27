package com.naqqa.elasticsearch.security.fls;

import com.naqqa.elasticsearch.security.authz.FieldSecurity;
import com.naqqa.elasticsearch.security.authz.IndicesPrivileges;
import com.naqqa.elasticsearch.security.authz.RoleDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

public final class FlsService {

    public Optional<Predicate<String>> resolveFieldPredicate(List<RoleDescriptor> roles, String index) {
        List<FieldSecurity> applicable = new ArrayList<>();
        boolean matchedAny = false;
        for (RoleDescriptor role : roles) {
            for (IndicesPrivileges privileges : role.indicesPrivileges()) {
                if (!privileges.matchesIndex(index)) {
                    continue;
                }
                matchedAny = true;
                if (privileges.fieldSecurity() == null) {
                    return Optional.empty();
                }
                applicable.add(privileges.fieldSecurity());
            }
        }
        if (!matchedAny || applicable.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(field -> applicable.stream().anyMatch(fs -> fs.isGranted(field)));
    }

    public Map<String, Object> filterSource(Map<String, Object> source, Predicate<String> allowed) {
        return filterMap(source, "", allowed);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filterMap(Map<String, Object> map, String prefix, Predicate<String> allowed) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?>) {
                Map<String, Object> filteredNested = filterMap((Map<String, Object>) value, path, allowed);
                if (!filteredNested.isEmpty() || allowed.test(path)) {
                    result.put(entry.getKey(), filteredNested);
                }
            } else if (value instanceof List<?> list) {
                if (!list.isEmpty() && list.get(0) instanceof Map) {
                    List<Object> filteredList = new ArrayList<>();
                    for (Object element : list) {
                        if (element instanceof Map<?, ?>) {
                            filteredList.add(filterMap((Map<String, Object>) element, path, allowed));
                        }
                    }
                    if (!filteredList.isEmpty() || allowed.test(path)) {
                        result.put(entry.getKey(), filteredList);
                    }
                } else if (allowed.test(path)) {
                    result.put(entry.getKey(), value);
                }
            } else if (allowed.test(path)) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }
}
