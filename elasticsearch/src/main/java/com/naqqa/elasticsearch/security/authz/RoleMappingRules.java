package com.naqqa.elasticsearch.security.authz;

import com.naqqa.elasticsearch.security.support.WildcardMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RoleMappingRules {

    private RoleMappingRules() {
    }

    public interface Context {
        Object fieldValue(String field);
    }

    public static boolean matches(Object rule, Context ctx) {
        if (!(rule instanceof Map<?, ?> map)) {
            return false;
        }
        if (map.containsKey("any")) {
            for (Object child : asList(map.get("any"))) {
                if (matches(child, ctx)) {
                    return true;
                }
            }
            return false;
        }
        if (map.containsKey("all")) {
            for (Object child : asList(map.get("all"))) {
                if (!matches(child, ctx)) {
                    return false;
                }
            }
            return true;
        }
        if (map.containsKey("except")) {
            return !matches(map.get("except"), ctx);
        }
        if (map.containsKey("field")) {
            Object fieldSpec = map.get("field");
            if (!(fieldSpec instanceof Map<?, ?> fields) || fields.isEmpty()) {
                return false;
            }
            for (Map.Entry<?, ?> e : fields.entrySet()) {
                if (!matchesField(ctx.fieldValue(String.valueOf(e.getKey())), e.getValue())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean matchesField(Object actual, Object expected) {
        List<Object> actualValues = asList(actual);
        List<Object> expectedValues = asList(expected);
        if (actualValues.isEmpty()) {
            return expectedValues.stream().anyMatch(Objects::isNull);
        }
        for (Object a : actualValues) {
            for (Object e : expectedValues) {
                if (valueMatches(a, e)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean valueMatches(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        if (actual instanceof String actualString && expected instanceof String expectedString) {
            return WildcardMatcher.matches(expectedString, actualString);
        }
        return Objects.equals(actual, expected);
    }

    private static List<Object> asList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of(value);
    }
}
