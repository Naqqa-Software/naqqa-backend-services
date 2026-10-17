package com.naqqa.auth.sso.scim;

import com.naqqa.sso.scim.ScimResource;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ScimDocuments {

    private ScimDocuments() {
    }

    public static String escape(String key) {
        StringBuilder sb = new StringBuilder(key.length() + 4);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '%') {
                sb.append("%25");
            } else if (c == '.') {
                sb.append("%2E");
            } else if (c == '$' && i == 0) {
                sb.append("%24");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String unescape(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '%' && i + 2 < key.length()) {
                String code = key.substring(i + 1, i + 3);
                switch (code) {
                    case "25" -> {
                        sb.append('%');
                        i += 2;
                        continue;
                    }
                    case "2E" -> {
                        sb.append('.');
                        i += 2;
                        continue;
                    }
                    case "24" -> {
                        sb.append('$');
                        i += 2;
                        continue;
                    }
                    default -> {
                    }
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static Object encodeValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            Document d = new Document();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                d.put(escape((String) e.getKey()), encodeValue(e.getValue()));
            }
            return d;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(encodeValue(o));
            }
            return out;
        }
        return v;
    }

    static Object decodeValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(unescape((String) e.getKey()), decodeValue(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(decodeValue(o));
            }
            return out;
        }
        if (v instanceof Integer i) {
            return i.longValue();
        }
        return v;
    }

    static Document shadow(Map<String, Object> attributes) {
        Document lc = new Document();
        for (Map.Entry<String, Object> e : attributes.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) {
                lc.put(escape(e.getKey()), s.toLowerCase(Locale.ROOT));
            } else if (v instanceof Map<?, ?> m) {
                Document sub = new Document();
                for (Map.Entry<?, ?> se : m.entrySet()) {
                    if (se.getValue() instanceof String ss) {
                        sub.put(escape((String) se.getKey()), ss.toLowerCase(Locale.ROOT));
                    }
                }
                if (!sub.isEmpty()) {
                    lc.put(escape(e.getKey()), sub);
                }
            }
        }
        return lc;
    }

    static String key(String tenantId, String id) {
        return tenantId + "|" + id;
    }

    static Document toDocument(ScimResource r, String uniqueAttribute, String uniqueField) {
        Document doc = new Document("_id", key(r.tenantId(), r.id()))
                .append("tenantId", r.tenantId())
                .append("rid", r.id())
                .append("version", r.version())
                .append("created", Date.from(r.created()))
                .append("lastModified", Date.from(r.lastModified()))
                .append("attributes", encodeValue(r.attributes()))
                .append("lc", shadow(r.attributes()));
        Object unique = r.attributes().get(uniqueAttribute);
        if (unique instanceof String s) {
            doc.append(uniqueField, s.toLowerCase(Locale.ROOT));
        }
        return doc;
    }

    @SuppressWarnings("unchecked")
    static ScimResource fromDocument(Document d) {
        Object attrs = decodeValue(d.get("attributes", Document.class));
        Number version = d.get("version", Number.class);
        return new ScimResource(d.getString("tenantId"), d.getString("rid"),
                attrs == null ? new LinkedHashMap<>() : (Map<String, Object>) attrs, version == null ? 1 : version.longValue(),
                d.getDate("created").toInstant(), d.getDate("lastModified").toInstant());
    }
}
