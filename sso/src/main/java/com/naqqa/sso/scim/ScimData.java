package com.naqqa.sso.scim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ScimData {
    private ScimData() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    static Object deepCopy(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put((String) e.getKey(), deepCopy(e.getValue()));
            }
            return out;
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object v : l) {
                out.add(deepCopy(v));
            }
            return out;
        }
        return o;
    }

    static Map<String, Object> mutableCopy(Map<String, Object> m) {
        return map(deepCopy(m));
    }

    static Object immutable(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put((String) e.getKey(), immutable(e.getValue()));
            }
            return Collections.unmodifiableMap(out);
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object v : l) {
                out.add(immutable(v));
            }
            return Collections.unmodifiableList(out);
        }
        return o;
    }
}
