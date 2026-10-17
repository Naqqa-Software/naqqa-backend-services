package com.naqqa.sso.json;

import com.naqqa.sso.SsoException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonParser {
    private final String in;
    private final JsonLimits limits;
    private int pos;
    private int depth;

    JsonParser(String in, JsonLimits limits) {
        this.in = in;
        this.limits = limits;
    }

    Object parseDocument() {
        if (in.length() > limits.maxSize()) {
            throw new SsoException("json.too_large");
        }
        skipWs();
        Object value = parseValue();
        skipWs();
        if (pos != in.length()) {
            throw new SsoException("json.trailing_data");
        }
        return value;
    }

    private Object parseValue() {
        if (pos >= in.length()) {
            throw new SsoException("json.unexpected_end");
        }
        char c = in.charAt(pos);
        switch (c) {
            case '{':
                return parseObject();
            case '[':
                return parseArray();
            case '"':
                return parseString();
            case 't':
                expectLiteral("true");
                return Boolean.TRUE;
            case 'f':
                expectLiteral("false");
                return Boolean.FALSE;
            case 'n':
                expectLiteral("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return parseNumber();
                }
                throw new SsoException("json.unexpected_char");
        }
    }

    private void enter() {
        depth++;
        if (depth > limits.maxDepth()) {
            throw new SsoException("json.too_deep");
        }
    }

    private Map<String, Object> parseObject() {
        enter();
        pos++;
        Map<String, Object> map = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            pos++;
            depth--;
            return map;
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw new SsoException("json.expected_key");
            }
            String key = parseString();
            skipWs();
            if (peek() != ':') {
                throw new SsoException("json.expected_colon");
            }
            pos++;
            skipWs();
            Object value = parseValue();
            if (map.containsKey(key)) {
                throw new SsoException("json.duplicate_key");
            }
            map.put(key, value);
            skipWs();
            char c = peek();
            pos++;
            if (c == ',') {
                continue;
            }
            if (c == '}') {
                depth--;
                return map;
            }
            throw new SsoException("json.expected_comma");
        }
    }

    private List<Object> parseArray() {
        enter();
        pos++;
        List<Object> list = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            pos++;
            depth--;
            return list;
        }
        while (true) {
            skipWs();
            list.add(parseValue());
            skipWs();
            char c = peek();
            pos++;
            if (c == ',') {
                continue;
            }
            if (c == ']') {
                depth--;
                return list;
            }
            throw new SsoException("json.expected_comma");
        }
    }

    private String parseString() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= in.length()) {
                throw new SsoException("json.unterminated_string");
            }
            char c = in.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c < 0x20) {
                throw new SsoException("json.control_char");
            }
            if (c == '\\') {
                c = readEscape();
            }
            sb.append(c);
        }
        String s = sb.toString();
        checkSurrogates(s);
        return s;
    }

    private char readEscape() {
        if (pos >= in.length()) {
            throw new SsoException("json.unterminated_string");
        }
        char e = in.charAt(pos++);
        switch (e) {
            case '"':
                return '"';
            case '\\':
                return '\\';
            case '/':
                return '/';
            case 'b':
                return '\b';
            case 'f':
                return '\f';
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 't':
                return '\t';
            case 'u':
                if (pos + 4 > in.length()) {
                    throw new SsoException("json.bad_escape");
                }
                int v = 0;
                for (int i = 0; i < 4; i++) {
                    int d = Character.digit(in.charAt(pos++), 16);
                    if (d < 0) {
                        throw new SsoException("json.bad_escape");
                    }
                    v = (v << 4) | d;
                }
                return (char) v;
            default:
                throw new SsoException("json.bad_escape");
        }
    }

    static void checkSurrogates(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    throw new SsoException("json.lone_surrogate");
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new SsoException("json.lone_surrogate");
            }
        }
    }

    private Object parseNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (pos >= in.length()) {
            throw new SsoException("json.bad_number");
        }
        char c = in.charAt(pos);
        if (c == '0') {
            pos++;
            if (pos < in.length() && isDigit(in.charAt(pos))) {
                throw new SsoException("json.leading_zero");
            }
        } else if (c >= '1' && c <= '9') {
            digits();
        } else {
            throw new SsoException("json.bad_number");
        }
        boolean integral = true;
        if (pos < in.length() && in.charAt(pos) == '.') {
            integral = false;
            pos++;
            if (digits() == 0) {
                throw new SsoException("json.bad_number");
            }
        }
        if (pos < in.length() && (in.charAt(pos) == 'e' || in.charAt(pos) == 'E')) {
            integral = false;
            pos++;
            if (pos < in.length() && (in.charAt(pos) == '+' || in.charAt(pos) == '-')) {
                pos++;
            }
            if (digits() == 0) {
                throw new SsoException("json.bad_number");
            }
        }
        String text = in.substring(start, pos);
        if (text.length() > 400) {
            throw new SsoException("json.bad_number");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(text);
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new SsoException("json.bad_number");
        }
        if (Math.abs((long) value.scale()) > 1000) {
            throw new SsoException("json.bad_number");
        }
        if (integral) {
            try {
                return value.longValueExact();
            } catch (ArithmeticException ex) {
                return value;
            }
        }
        return value;
    }

    private int digits() {
        int n = 0;
        while (pos < in.length() && isDigit(in.charAt(pos))) {
            pos++;
            n++;
        }
        return n;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private void expectLiteral(String lit) {
        if (!in.startsWith(lit, pos)) {
            throw new SsoException("json.unexpected_char");
        }
        pos += lit.length();
    }

    private char peek() {
        if (pos >= in.length()) {
            throw new SsoException("json.unexpected_end");
        }
        return in.charAt(pos);
    }

    private void skipWs() {
        while (pos < in.length()) {
            char c = in.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }
}
