package com.naqqa.sso.scim;

import com.naqqa.sso.SsoException;
import java.util.List;
import java.util.Map;

public record ListQuery(
        String filter,
        String sortBy,
        String sortOrder,
        Integer startIndex,
        Integer count,
        List<String> attributes,
        List<String> excludedAttributes) {

    public ListQuery {
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
        excludedAttributes = excludedAttributes == null ? List.of() : List.copyOf(excludedAttributes);
    }

    public static ListQuery all() {
        return new ListQuery(null, null, null, null, null, null, null);
    }

    public static ListQuery filter(String filter) {
        return new ListQuery(filter, null, null, null, null, null, null);
    }

    public static ListQuery fromParams(Map<String, String> p) {
        return new ListQuery(
                p.get("filter"),
                p.get("sortBy"),
                p.get("sortOrder"),
                intOrNull(p.get("startIndex")),
                intOrNull(p.get("count")),
                csv(p.get("attributes")),
                csv(p.get("excludedAttributes")));
    }

    public static ListQuery fromBody(Map<String, Object> body) {
        Object schemas = body.get("schemas");
        if (!(schemas instanceof List<?> l) || !l.contains(ScimSchemas.SEARCH_REQUEST)) {
            throw new SsoException("scim.invalid_syntax");
        }
        return new ListQuery(
                str(body.get("filter")),
                str(body.get("sortBy")),
                str(body.get("sortOrder")),
                num(body.get("startIndex")),
                num(body.get("count")),
                strings(body.get("attributes")),
                strings(body.get("excludedAttributes")));
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        if (!(o instanceof String s)) {
            throw new SsoException("scim.invalid_value");
        }
        return s;
    }

    private static Integer num(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                && Math.abs(n.doubleValue()) < Integer.MAX_VALUE) {
            return n.intValue();
        }
        throw new SsoException("scim.invalid_value");
    }

    private static List<String> strings(Object o) {
        if (o == null) {
            return List.of();
        }
        if (!(o instanceof List<?> l)) {
            throw new SsoException("scim.invalid_value");
        }
        return l.stream().map(ListQuery::str).toList();
    }

    private static Integer intOrNull(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        if (s.length() > 9) {
            throw new SsoException("scim.invalid_value");
        }
        int start = s.charAt(0) == '-' ? 1 : 0;
        if (start == s.length()) {
            throw new SsoException("scim.invalid_value");
        }
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                throw new SsoException("scim.invalid_value");
            }
        }
        return Integer.parseInt(s);
    }

    private static List<String> csv(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        if (s.length() > 1024) {
            throw new SsoException("scim.too_many");
        }
        return List.of(s.split(","));
    }
}
