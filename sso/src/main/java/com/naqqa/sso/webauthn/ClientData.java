package com.naqqa.sso.webauthn;

import com.naqqa.sso.json.Json;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public record ClientData(String type, byte[] challenge, String origin, boolean crossOrigin, String topOrigin,
                         byte[] hash) {
    public static final String TYPE_CREATE = "webauthn.create";
    public static final String TYPE_GET = "webauthn.get";
    public static final int MAX_BYTES = 8192;

    public ClientData {
        challenge = challenge.clone();
        hash = hash.clone();
    }

    @Override
    public byte[] challenge() {
        return challenge.clone();
    }

    @Override
    public byte[] hash() {
        return hash.clone();
    }

    public static ClientData parse(byte[] json) {
        if (json == null || json.length == 0 || json.length > MAX_BYTES) {
            throw new SsoException("webauthn.client_data.invalid");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(json)).toString();
        } catch (CharacterCodingException e) {
            throw new SsoException("webauthn.client_data.invalid");
        }
        Map<String, Object> m;
        try {
            m = Json.parseObject(text, WebAuthnLimits.JSON);
        } catch (SsoException e) {
            throw new SsoException("webauthn.client_data.invalid");
        }
        if (!(m.get("type") instanceof String type) || !(m.get("challenge") instanceof String ch)
                || !(m.get("origin") instanceof String origin)) {
            throw new SsoException("webauthn.client_data.invalid");
        }
        Object co = m.get("crossOrigin");
        if (co != null && !(co instanceof Boolean)) {
            throw new SsoException("webauthn.client_data.invalid");
        }
        Object top = m.get("topOrigin");
        if (top != null && !(top instanceof String)) {
            throw new SsoException("webauthn.client_data.invalid");
        }
        if (m.get("tokenBinding") instanceof Map<?, ?> tb && "present".equals(tb.get("status"))) {
            throw new SsoException("webauthn.client_data.token_binding_unsupported");
        }
        byte[] challenge;
        try {
            challenge = Bytes.b64urlDecode(ch);
        } catch (IllegalArgumentException e) {
            throw new SsoException("webauthn.client_data.invalid");
        }
        return new ClientData(type, challenge, origin, Boolean.TRUE.equals(co), (String) top, Hashes.sha256(json));
    }
}
