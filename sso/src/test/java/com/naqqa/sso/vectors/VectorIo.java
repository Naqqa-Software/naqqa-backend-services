package com.naqqa.sso.vectors;

import com.naqqa.sso.json.Json;
import com.naqqa.sso.json.JsonLimits;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class VectorIo {
    static final JsonLimits LIMITS = new JsonLimits(32, 8 * 1024 * 1024);
    static final String WRITE_PROPERTY = "sso.vectors.write";
    static final String DIR_PROPERTY = "sso.vectors.dir";

    private VectorIo() {
    }

    static boolean writeMode() {
        return Boolean.getBoolean(WRITE_PROPERTY);
    }

    static Path dir() {
        return Path.of(System.getProperty(DIR_PROPERTY, "vectors")).toAbsolutePath();
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    static Map<String, Object> load(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Json.parseObject(Files.readString(file, StandardCharsets.UTF_8), LIMITS);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> casesByName(Map<String, Object> doc) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (doc == null || !(doc.get("cases") instanceof List<?> cases)) {
            return out;
        }
        for (Object o : cases) {
            Map<String, Object> c = (Map<String, Object>) o;
            out.put((String) c.get("name"), c);
        }
        return out;
    }

    static Map<String, Object> docCase(String name, String description, Map<String, Object> input, Map<String, Object> expected) {
        return m("name", name, "description", description, "input", input, "expected", expected);
    }

    static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
    }

    private static void write(StringBuilder sb, Object v, int level) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            string(sb, s);
        } else if (v instanceof Boolean || v instanceof Long || v instanceof Integer) {
            sb.append(v);
        } else if (v instanceof BigDecimal d) {
            sb.append(d.toPlainString());
        } else if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int n = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                indent(sb, level + 1);
                string(sb, (String) e.getKey());
                sb.append(": ");
                write(sb, e.getValue(), level + 1);
                sb.append(++n < map.size() ? ",\n" : "\n");
            }
            indent(sb, level);
            sb.append('}');
        } else if (v instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            boolean scalars = list.stream().allMatch(x -> x == null || x instanceof String || x instanceof Number || x instanceof Boolean);
            if (scalars) {
                List<String> parts = new ArrayList<>();
                for (Object x : list) {
                    StringBuilder one = new StringBuilder();
                    write(one, x, 0);
                    parts.add(one.toString());
                }
                String joined = String.join(", ", parts);
                if (joined.length() < 100) {
                    sb.append('[').append(joined).append(']');
                    return;
                }
            }
            sb.append("[\n");
            int n = 0;
            for (Object x : list) {
                indent(sb, level + 1);
                write(sb, x, level + 1);
                sb.append(++n < list.size() ? ",\n" : "\n");
            }
            indent(sb, level);
            sb.append(']');
        } else {
            throw new IllegalArgumentException("unsupported " + v.getClass());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
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
