package com.naqqa.sso.scim;

import com.naqqa.sso.SsoException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ScimNormalizer {
    static final int MAX_STRING = 2048;
    static final int MAX_MULTI = 10000;

    private ScimNormalizer() {
    }

    static Map<String, Object> normalize(ResourceDef def, Map<String, Object> in, boolean lenient) {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            String key = e.getKey();
            if (!seen.add(key.toLowerCase(Locale.ROOT))) {
                throw new SsoException("scim.invalid_syntax");
            }
            if (key.equalsIgnoreCase("schemas")) {
                continue;
            }
            String ext = def.extensionUrn(key);
            if (ext != null) {
                if (e.getValue() == null) {
                    continue;
                }
                if (!(e.getValue() instanceof Map<?, ?> m)) {
                    throw new SsoException("scim.invalid_value");
                }
                Map<String, Object> n = normalizeAttrs(def.extensions().get(ext), ScimData.map(m), lenient);
                requireAll(def.extensions().get(ext), n);
                if (!n.isEmpty()) {
                    out.put(ext, n);
                }
                continue;
            }
            AttrDef a = def.coreAttr(key);
            if (a == null) {
                throw new SsoException("scim.invalid_value");
            }
            if (a.mutability() == AttrDef.Mutability.READ_ONLY) {
                continue;
            }
            Object nv = value(a, e.getValue(), lenient);
            if (nv != null) {
                out.put(a.name(), nv);
            }
        }
        requireAll(def.core(), out);
        return out;
    }

    private static void requireAll(List<AttrDef> defs, Map<String, Object> out) {
        for (AttrDef d : defs) {
            if (d.required() && d.mutability() != AttrDef.Mutability.READ_ONLY) {
                Object v = out.get(d.name());
                if (v == null || (v instanceof String s && s.isBlank())) {
                    throw new SsoException("scim.invalid_value");
                }
            }
        }
    }

    private static Map<String, Object> normalizeAttrs(List<AttrDef> defs, Map<String, Object> in, boolean lenient) {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            if (!seen.add(e.getKey().toLowerCase(Locale.ROOT))) {
                throw new SsoException("scim.invalid_syntax");
            }
            AttrDef a = ResourceDef.find(defs, e.getKey());
            if (a == null) {
                throw new SsoException("scim.invalid_value");
            }
            if (a.mutability() == AttrDef.Mutability.READ_ONLY) {
                continue;
            }
            Object nv = value(a, e.getValue(), lenient);
            if (nv != null) {
                out.put(a.name(), nv);
            }
        }
        return out;
    }

    private static Object value(AttrDef a, Object v, boolean lenient) {
        if (v == null) {
            return null;
        }
        if (!a.multiValued()) {
            return single(a, v, lenient);
        }
        if (!(v instanceof List<?> list)) {
            throw new SsoException("scim.invalid_value");
        }
        if (list.size() > MAX_MULTI) {
            throw new SsoException("scim.too_many");
        }
        List<Object> out = new ArrayList<>();
        int primaries = 0;
        for (Object el : list) {
            Object n = single(a, el, lenient);
            if (n == null) {
                continue;
            }
            if (n instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("primary"))) {
                primaries++;
            }
            out.add(n);
        }
        if (primaries > 1) {
            throw new SsoException("scim.invalid_value");
        }
        return out.isEmpty() ? null : out;
    }

    private static Object single(AttrDef a, Object v, boolean lenient) {
        if (v == null) {
            return null;
        }
        switch (a.type()) {
            case COMPLEX -> {
                if (!(v instanceof Map<?, ?> m)) {
                    throw new SsoException("scim.invalid_value");
                }
                Map<String, Object> n = normalizeAttrs(a.subs(), ScimData.map(m), lenient);
                if (n.isEmpty()) {
                    return null;
                }
                requireAll(a.subs(), n);
                return n;
            }
            case BOOLEAN -> {
                if (v instanceof Boolean) {
                    return v;
                }
                if (lenient && v instanceof String s) {
                    if (s.equalsIgnoreCase("true")) {
                        return Boolean.TRUE;
                    }
                    if (s.equalsIgnoreCase("false")) {
                        return Boolean.FALSE;
                    }
                }
                throw new SsoException("scim.invalid_value");
            }
            default -> {
                if (!(v instanceof String s) || s.length() > MAX_STRING || s.indexOf('\u0000') >= 0) {
                    throw new SsoException("scim.invalid_value");
                }
                if (a.type() == AttrDef.Type.DATETIME) {
                    try {
                        Instant.parse(s);
                    } catch (RuntimeException e) {
                        throw new SsoException("scim.invalid_value");
                    }
                }
                return s;
            }
        }
    }
}
