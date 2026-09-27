package com.naqqa.elasticsearch.http;

import java.util.List;
import java.util.Map;

public final class Json {

    private Json() {
    }

    public static String write(Object value, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, pretty, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, boolean pretty, int depth) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map, pretty, depth);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list, pretty, depth);
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Number n) {
            sb.append(n);
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map, boolean pretty, int depth) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            newlineIndent(sb, pretty, depth + 1);
            writeString(sb, String.valueOf(entry.getKey()));
            sb.append(':');
            if (pretty) {
                sb.append(' ');
            }
            writeValue(sb, entry.getValue(), pretty, depth + 1);
        }
        newlineIndent(sb, pretty, depth);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list, boolean pretty, int depth) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        for (Object element : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            newlineIndent(sb, pretty, depth + 1);
            writeValue(sb, element, pretty, depth + 1);
        }
        newlineIndent(sb, pretty, depth);
        sb.append(']');
    }

    private static void newlineIndent(StringBuilder sb, boolean pretty, int depth) {
        if (!pretty) {
            return;
        }
        sb.append('\n');
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
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
        sb.append('"');
    }
}
