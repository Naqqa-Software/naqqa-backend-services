package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;

import java.util.Arrays;
import java.util.Map;

final class ResponseJson {
    private ResponseJson() {
    }

    static void requireType(Map<String, Object> m) {
        if (!"public-key".equals(m.get("type"))) {
            throw new SsoException("webauthn.response.invalid");
        }
    }

    static byte[] credentialId(Map<String, Object> m) {
        byte[] id = b64(m, "id", true);
        byte[] rawId = b64(m, "rawId", false);
        if (rawId != null && !Arrays.equals(id, rawId)) {
            throw new SsoException("webauthn.response.invalid");
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> response(Map<String, Object> m) {
        if (m.get("response") instanceof Map<?, ?> r) {
            return (Map<String, Object>) r;
        }
        throw new SsoException("webauthn.response.invalid");
    }

    static byte[] b64(Map<String, Object> m, String key, boolean required) {
        Object v = m.get(key);
        if (v == null && !required) {
            return null;
        }
        if (!(v instanceof String s)) {
            throw new SsoException("webauthn.response.invalid");
        }
        try {
            return Bytes.b64urlDecode(s);
        } catch (IllegalArgumentException e) {
            throw new SsoException("webauthn.response.invalid");
        }
    }
}
