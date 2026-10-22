package com.naqqa.sso.scim;

import com.naqqa.sso.SsoException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class ScimPatch {
    private record Target(String urn, AttrDef attr, FilterNode filter, AttrDef sub, String raw) {
    }

    private final ResourceDef def;
    private final FilterParser.Limits limits;
    private final Map<String, Object> doc;

    private ScimPatch(ResourceDef def, FilterParser.Limits limits, Map<String, Object> doc) {
        this.def = def;
        this.limits = limits;
        this.doc = doc;
    }

    static void apply(ResourceDef def, FilterParser.Limits limits, Map<String, Object> doc, Object operations,
            int maxOps) {
        if (!(operations instanceof List<?> ops) || ops.isEmpty()) {
            throw new SsoException("scim.invalid_syntax");
        }
        if (ops.size() > maxOps) {
            throw new SsoException("scim.too_many");
        }
        ScimPatch p = new ScimPatch(def, limits, doc);
        for (Object o : ops) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new SsoException("scim.invalid_syntax");
            }
            p.operation(ScimData.map(m));
        }
    }

    private void operation(Map<String, Object> op) {
        Map<String, Object> lower = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : op.entrySet()) {
            if (lower.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue()) != null) {
                throw new SsoException("scim.invalid_syntax");
            }
        }
        if (!(lower.get("op") instanceof String name)) {
            throw new SsoException("scim.invalid_syntax");
        }
        String kind = name.toLowerCase(Locale.ROOT);
        if (!kind.equals("add") && !kind.equals("remove") && !kind.equals("replace")) {
            throw new SsoException("scim.invalid_syntax");
        }
        Object pathObj = lower.get("path");
        if (pathObj != null && !(pathObj instanceof String)) {
            throw new SsoException("scim.invalid_path");
        }
        String path = (String) pathObj;
        if (path != null && path.isBlank()) {
            path = null;
        }
        Object value = ScimData.deepCopy(lower.get("value"));
        if (kind.equals("remove")) {
            if (path == null) {
                throw new SsoException("scim.no_target");
            }
            remove(target(path));
            return;
        }
        if (value == null) {
            throw new SsoException("scim.invalid_value");
        }
        if (path == null) {
            if (!(value instanceof Map<?, ?> m)) {
                throw new SsoException("scim.invalid_value");
            }
            spread(kind, ScimData.map(m));
            return;
        }
        Target t = target(path);
        if (t.attr == null) {
            if (!(value instanceof Map<?, ?> m)) {
                throw new SsoException("scim.invalid_value");
            }
            if (kind.equals("replace")) {
                doc.remove(t.urn);
            }
            for (Map.Entry<String, Object> e : ScimData.map(m).entrySet()) {
                set(kind, target(t.urn + ":" + e.getKey()), e.getValue());
            }
            return;
        }
        set(kind, t, value);
    }

    private void spread(String kind, Map<String, Object> value) {
        for (Map.Entry<String, Object> e : value.entrySet()) {
            String ext = def.extensionUrn(e.getKey());
            if (ext != null) {
                if (!(e.getValue() instanceof Map<?, ?> m)) {
                    throw new SsoException("scim.invalid_value");
                }
                if (kind.equals("replace")) {
                    doc.remove(ext);
                }
                for (Map.Entry<String, Object> x : ScimData.map(m).entrySet()) {
                    set(kind, target(ext + ":" + x.getKey()), x.getValue());
                }
                continue;
            }
            String key = e.getKey();
            if (key.equalsIgnoreCase("schemas")) {
                continue;
            }
            AttrPath ro = def.resolve(key.indexOf('[') >= 0 ? key.substring(0, key.indexOf('[')) : key);
            if (ro != null && ro.attr().mutability() == AttrDef.Mutability.READ_ONLY) {
                continue;
            }
            set(kind, target(key), e.getValue());
        }
    }

    private Target target(String raw) {
        if (raw.length() > 512) {
            throw new SsoException("scim.invalid_path");
        }
        String ext = def.extensionUrn(raw);
        if (ext != null) {
            return new Target(ext, null, null, null, raw);
        }
        int idx = raw.indexOf('[');
        if (idx < 0) {
            AttrPath p = def.resolve(raw);
            if (p == null) {
                throw new SsoException("scim.invalid_path");
            }
            guard(p.attr(), p.sub());
            return new Target(p.urn(), p.attr(), null, p.sub(), raw);
        }
        AttrPath head = def.resolve(raw.substring(0, idx));
        int close = raw.lastIndexOf(']');
        if (head == null || head.sub() != null || !head.attr().multiValued()
                || head.attr().type() != AttrDef.Type.COMPLEX || close < idx) {
            throw new SsoException("scim.invalid_path");
        }
        String tail = raw.substring(close + 1);
        AttrDef sub = null;
        if (!tail.isEmpty()) {
            if (tail.charAt(0) != '.') {
                throw new SsoException("scim.invalid_path");
            }
            sub = head.attr().sub(tail.substring(1));
            if (sub == null) {
                throw new SsoException("scim.invalid_path");
            }
        }
        guard(head.attr(), sub);
        FilterNode filter;
        try {
            filter = FilterParser.parseScoped(raw.substring(idx + 1, close), def, head.attr(), limits);
        } catch (SsoException e) {
            throw new SsoException("scim.invalid_path");
        }
        return new Target(head.urn(), head.attr(), filter, sub, raw);
    }

    private void guard(AttrDef attr, AttrDef sub) {
        if (attr.mutability() == AttrDef.Mutability.READ_ONLY
                || (sub != null && sub.mutability() == AttrDef.Mutability.READ_ONLY)) {
            throw new SsoException("scim.mutability");
        }
    }

    private Map<String, Object> box(Target t, boolean create) {
        if (t.urn == null) {
            return doc;
        }
        Object ext = doc.get(t.urn);
        if (ext instanceof Map<?, ?> m) {
            return ScimData.map(m);
        }
        if (!create) {
            return null;
        }
        Map<String, Object> n = new LinkedHashMap<>();
        doc.put(t.urn, n);
        return n;
    }

    private List<Map<String, Object>> matching(Target t, Map<String, Object> box) {
        Object v = box == null ? null : box.get(t.attr.name());
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object e : l) {
                if (e instanceof Map<?, ?> m && FilterEvaluator.matches(t.filter, ScimData.map(m))) {
                    out.add(ScimData.map(m));
                }
            }
        }
        if (out.isEmpty()) {
            throw new SsoException("scim.no_target");
        }
        return out;
    }

    private void set(String kind, Target t, Object value) {
        if (t.attr == null) {
            throw new SsoException("scim.invalid_path");
        }
        boolean replace = kind.equals("replace");
        Map<String, Object> box = box(t, true);
        String key = t.attr.name();
        if (t.filter != null) {
            for (Map<String, Object> el : matching(t, box)) {
                if (t.sub != null) {
                    el.put(t.sub.name(), value);
                } else {
                    if (!(value instanceof Map<?, ?> m)) {
                        throw new SsoException("scim.invalid_value");
                    }
                    if (replace) {
                        el.clear();
                    }
                    el.putAll(ScimData.map(m));
                    if (Boolean.TRUE.equals(el.get("primary"))) {
                        demote(box.get(key), el);
                    }
                }
            }
            return;
        }
        if (t.sub != null) {
            if (t.attr.multiValued()) {
                throw new SsoException("scim.invalid_path");
            }
            Object cur = box.get(key);
            Map<String, Object> m;
            if (cur instanceof Map<?, ?> cm) {
                m = ScimData.map(cm);
            } else {
                m = new LinkedHashMap<>();
                box.put(key, m);
            }
            m.put(t.sub.name(), value);
            return;
        }
        if (t.attr.multiValued()) {
            List<Object> incoming = new ArrayList<>();
            if (value instanceof List<?> l) {
                incoming.addAll(l);
            } else {
                incoming.add(value);
            }
            for (Object o : incoming) {
                if (!(o instanceof Map<?, ?>)) {
                    throw new SsoException("scim.invalid_value");
                }
            }
            if (replace) {
                box.put(key, incoming);
                return;
            }
            Object cur = box.get(key);
            List<Object> target;
            if (cur instanceof List<?> cl) {
                target = ScimData.list(cl);
            } else {
                target = new ArrayList<>();
                box.put(key, target);
            }
            for (Object o : incoming) {
                if (!containsEqual(target, o)) {
                    target.add(o);
                }
                if (Boolean.TRUE.equals(ScimData.map(o).get("primary"))) {
                    demote(target, ScimData.map(o));
                }
            }
            return;
        }
        if (t.attr.type() == AttrDef.Type.COMPLEX) {
            if (!(value instanceof Map<?, ?> m)) {
                throw new SsoException("scim.invalid_value");
            }
            Object cur = box.get(key);
            if (!replace && cur instanceof Map<?, ?> cm) {
                ScimData.map(cm).putAll(ScimData.map(m));
            } else {
                box.put(key, ScimData.map(m));
            }
            return;
        }
        box.put(key, value);
    }

    private static boolean containsEqual(List<Object> list, Object o) {
        for (Object e : list) {
            if (Objects.equals(e, o)) {
                return true;
            }
        }
        return false;
    }

    private static void demote(Object list, Object keep) {
        if (list instanceof List<?> l) {
            for (Object e : l) {
                if (e != keep && e instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("primary"))) {
                    ScimData.map(m).put("primary", Boolean.FALSE);
                }
            }
        }
    }

    private void remove(Target t) {
        if (t.attr == null) {
            doc.remove(t.urn);
            return;
        }
        Map<String, Object> box = box(t, false);
        if (box == null) {
            if (t.filter != null) {
                throw new SsoException("scim.no_target");
            }
            return;
        }
        String key = t.attr.name();
        if (t.filter != null) {
            List<Map<String, Object>> hits = matching(t, box);
            if (t.sub != null) {
                for (Map<String, Object> el : hits) {
                    el.remove(t.sub.name());
                }
            } else {
                Iterator<Object> it = ScimData.list(box.get(key)).iterator();
                while (it.hasNext()) {
                    Object e = it.next();
                    for (Map<String, Object> h : hits) {
                        if (h == e) {
                            it.remove();
                            break;
                        }
                    }
                }
            }
        } else if (t.sub != null) {
            Object cur = box.get(key);
            if (cur instanceof Map<?, ?> m) {
                ScimData.map(m).remove(t.sub.name());
            } else if (cur instanceof List<?> l) {
                for (Object e : l) {
                    if (e instanceof Map<?, ?> m) {
                        ScimData.map(m).remove(t.sub.name());
                    }
                }
            }
        } else {
            box.remove(key);
        }
        if (t.urn != null && box.isEmpty()) {
            doc.remove(t.urn);
        }
    }
}
