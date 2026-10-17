package com.naqqa.auth.sso.support;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class SsoErrorBody {

    private SsoErrorBody() {
    }

    public static Map<String, Object> of(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return body;
    }

    public static String codeOf(String coreCode) {
        if (coreCode == null || coreCode.isBlank()) {
            return "INVALID_REQUEST";
        }
        return coreCode.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }

    public static String json(String code, String message) {
        return "{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}";
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : (s == null ? "" : s).toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
