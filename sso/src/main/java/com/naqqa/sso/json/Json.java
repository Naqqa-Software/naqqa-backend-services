package com.naqqa.sso.json;

import com.naqqa.sso.SsoException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Json {
    private Json() {
    }

    public static Object parse(String text) {
        return parse(text, JsonLimits.DEFAULT);
    }

    public static Object parse(String text, JsonLimits limits) {
        if (text == null) {
            throw new SsoException("json.null_input");
        }
        return new JsonParser(text, limits).parseDocument();
    }

    public static Object parse(byte[] utf8, JsonLimits limits) {
        if (utf8 == null) {
            throw new SsoException("json.null_input");
        }
        if (utf8.length > limits.maxSize()) {
            throw new SsoException("json.too_large");
        }
        if (utf8.length >= 3 && (utf8[0] & 0xFF) == 0xEF && (utf8[1] & 0xFF) == 0xBB && (utf8[2] & 0xFF) == 0xBF) {
            throw new SsoException("json.bom");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new SsoException("json.bad_utf8");
        }
        return parse(text, limits);
    }

    public static Object parse(byte[] utf8) {
        return parse(utf8, JsonLimits.DEFAULT);
    }

    public static Map<String, Object> parseObject(String text) {
        return parseObject(text, JsonLimits.DEFAULT);
    }

    public static Map<String, Object> parseObject(String text, JsonLimits limits) {
        return asObject(parse(text, limits));
    }

    public static Map<String, Object> parseObject(byte[] utf8) {
        return asObject(parse(utf8, JsonLimits.DEFAULT));
    }

    public static Map<String, Object> parseObject(byte[] utf8, JsonLimits limits) {
        return asObject(parse(utf8, limits));
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        new JsonWriter(sb, false).value(value, 0);
        return sb.toString();
    }

    public static String writeCanonical(Object value) {
        StringBuilder sb = new StringBuilder();
        new JsonWriter(sb, true).value(value, 0);
        return sb.toString();
    }

    public static byte[] writeUtf8(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (!(value instanceof Map)) {
            throw new SsoException("json.expected_object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        if (!(value instanceof List)) {
            throw new SsoException("json.expected_array");
        }
        return (List<Object>) value;
    }

    public static String optString(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String)) {
            throw new SsoException("json.type_mismatch");
        }
        return (String) v;
    }

    public static String requireString(Map<String, Object> obj, String key) {
        String v = optString(obj, key);
        if (v == null) {
            throw new SsoException("json.missing_member");
        }
        return v;
    }

    public static Long optLong(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Long)) {
            throw new SsoException("json.type_mismatch");
        }
        return (Long) v;
    }

    public static Boolean optBoolean(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Boolean)) {
            throw new SsoException("json.type_mismatch");
        }
        return (Boolean) v;
    }

    public static Map<String, Object> optObject(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v == null ? null : asObject(v);
    }

    public static List<String> optStringList(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (v == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object o : asList(v)) {
            if (!(o instanceof String)) {
                throw new SsoException("json.type_mismatch");
            }
            out.add((String) o);
        }
        return out;
    }

    static final class JsonWriter {
        private final StringBuilder sb;
        private final boolean sorted;

        JsonWriter(StringBuilder sb, boolean sorted) {
            this.sb = sb;
            this.sorted = sorted;
        }

        void value(Object v, int depth) {
            if (depth > JsonLimits.DEFAULT_MAX_DEPTH) {
                throw new SsoException("json.too_deep");
            }
            if (v == null) {
                sb.append("null");
            } else if (v instanceof String) {
                string((String) v);
            } else if (v instanceof Boolean) {
                sb.append(((Boolean) v) ? "true" : "false");
            } else if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte || v instanceof BigInteger) {
                sb.append(v.toString());
            } else if (v instanceof BigDecimal) {
                sb.append(((BigDecimal) v).toString());
            } else if (v instanceof Double || v instanceof Float) {
                double d = ((Number) v).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new SsoException("json.non_finite");
                }
                if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                    sb.append((long) d);
                } else {
                    sb.append(BigDecimal.valueOf(d).toString());
                }
            } else if (v instanceof Map) {
                object((Map<?, ?>) v, depth + 1);
            } else if (v instanceof Collection) {
                sb.append('[');
                boolean first = true;
                for (Object o : (Collection<?>) v) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    value(o, depth + 1);
                }
                sb.append(']');
            } else {
                throw new SsoException("json.unsupported_type");
            }
        }

        private void object(Map<?, ?> map, int depth) {
            Map<?, ?> view = map;
            if (sorted) {
                TreeMap<String, Object> t = new TreeMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!(e.getKey() instanceof String)) {
                        throw new SsoException("json.non_string_key");
                    }
                    t.put((String) e.getKey(), e.getValue());
                }
                view = t;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : view.entrySet()) {
                if (!(e.getKey() instanceof String)) {
                    throw new SsoException("json.non_string_key");
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                string((String) e.getKey());
                sb.append(':');
                value(e.getValue(), depth);
            }
            sb.append('}');
        }

        private void string(String s) {
            JsonParser.checkSurrogates(s);
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':
                        sb.append("\\\"");
                        break;
                    case '\\':
                        sb.append("\\\\");
                        break;
                    case '\b':
                        sb.append("\\b");
                        break;
                    case '\f':
                        sb.append("\\f");
                        break;
                    case '\n':
                        sb.append("\\n");
                        break;
                    case '\r':
                        sb.append("\\r");
                        break;
                    case '\t':
                        sb.append("\\t");
                        break;
                    default:
                        if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append('"');
        }
    }
}
