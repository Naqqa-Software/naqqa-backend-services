package com.naqqa.elasticsearch.script;

import java.lang.reflect.Array;
import java.util.Map;

public final class ScriptJson {

    private ScriptJson() {
    }

    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    public static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof CharSequence cs) {
            quote(sb, cs.toString());
        } else if (v instanceof Character c) {
            quote(sb, String.valueOf(c));
        } else if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                quote(sb, d.toString());
            } else {
                sb.append(d);
            }
        } else if (v instanceof Float f) {
            if (f.isNaN() || f.isInfinite()) {
                quote(sb, f.toString());
            } else {
                sb.append(f);
            }
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (v.getClass().isArray()) {
            sb.append('[');
            int n = Array.getLength(v);
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, Array.get(v, i));
            }
            sb.append(']');
        } else if (v instanceof GeoPoint gp) {
            sb.append("{\"lat\":").append(gp.lat()).append(",\"lon\":").append(gp.lon()).append('}');
        } else {
            quote(sb, v.toString());
        }
    }

    public static void quote(StringBuilder sb, String s) {
        sb.append('"');
        escape(sb, s);
        sb.append('"');
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        escape(sb, s);
        return sb.toString();
    }

    public static void escape(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }
}
