package com.naqqa.sso.jose;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.json.JsonLimits;
import com.naqqa.sso.util.Bytes;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class Jws {
    public static final int MAX_TOKEN_SIZE = 16 * 1024;
    static final Set<String> FORBIDDEN_HEADERS = Set.of("jku", "x5u", "jwk", "x5c", "b64");
    private static final Set<String> RESERVED_HEADERS = Set.of("alg", "kid", "crit", "enc", "zip");
    private static final JsonLimits HEADER_LIMITS = new JsonLimits(8, MAX_TOKEN_SIZE);

    private Jws() {
    }

    public record Verified(Map<String, Object> header, byte[] payload, SigningKey key) {
        public Verified {
            header = Collections.unmodifiableMap(new LinkedHashMap<>(header));
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        public String payloadUtf8() {
            return new String(payload, StandardCharsets.UTF_8);
        }

        public String kid() {
            return key.kid();
        }
    }

    record Parts(String headerB64, String payloadB64, Map<String, Object> header, byte[] payload, byte[] signature) {
    }

    public static String sign(SigningKey key, byte[] payload) {
        return sign(key, Map.of(), payload);
    }

    public static String sign(SigningKey key, Map<String, Object> extraHeader, byte[] payload) {
        if (key == null || !key.canSign()) {
            throw new SsoException("jose.key_cannot_sign");
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", key.alg().jwa());
        header.put("kid", key.kid());
        for (Map.Entry<String, Object> e : extraHeader.entrySet()) {
            if (RESERVED_HEADERS.contains(e.getKey()) || FORBIDDEN_HEADERS.contains(e.getKey())) {
                throw new SsoException("jose.header_forbidden");
            }
            header.put(e.getKey(), e.getValue());
        }
        String signingInput = Bytes.b64url(Json.writeUtf8(header)) + "." + Bytes.b64url(payload);
        byte[] sig = key.sign(signingInput.getBytes(StandardCharsets.US_ASCII));
        String token = signingInput + "." + Bytes.b64url(sig);
        if (token.length() > MAX_TOKEN_SIZE) {
            throw new SsoException("jose.too_large");
        }
        return token;
    }

    public static Verified verify(String token, KeyRing ring) {
        Parts p = parse(token);
        Object kidValue = p.header().get("kid");
        if (kidValue == null) {
            throw new SsoException("jose.kid_missing");
        }
        if (!(kidValue instanceof String) || !SigningKey.isValidKid((String) kidValue)) {
            throw new SsoException("jose.kid_invalid");
        }
        SigningKey key = ring.find((String) kidValue).orElseThrow(() -> new SsoException("jose.kid_unknown"));
        return check(p, key);
    }

    public static Verified verify(String token, SigningKey key) {
        Parts p = parse(token);
        Object kidValue = p.header().get("kid");
        if (kidValue != null && !(kidValue instanceof String && kidValue.equals(key.kid()))) {
            throw new SsoException("jose.kid_mismatch");
        }
        return check(p, key);
    }

    private static Verified check(Parts p, SigningKey key) {
        String alg = (String) p.header().get("alg");
        if (!key.alg().jwa().equals(alg)) {
            throw new SsoException("jose.alg_mismatch");
        }
        byte[] input = (p.headerB64() + "." + p.payloadB64()).getBytes(StandardCharsets.US_ASCII);
        if (!key.verify(input, p.signature())) {
            throw new SsoException("jose.signature_invalid");
        }
        return new Verified(p.header(), p.payload(), key.publicOnly());
    }

    public static Map<String, Object> peekHeader(String token) {
        return Collections.unmodifiableMap(parse(token).header());
    }

    static Parts parse(String token) {
        if (token == null || token.isEmpty()) {
            throw new SsoException("jose.malformed");
        }
        if (token.length() > MAX_TOKEN_SIZE) {
            throw new SsoException("jose.too_large");
        }
        int d1 = token.indexOf('.');
        int d2 = d1 < 0 ? -1 : token.indexOf('.', d1 + 1);
        if (d1 < 0 || d2 < 0 || token.indexOf('.', d2 + 1) >= 0) {
            throw new SsoException("jose.malformed");
        }
        String h = token.substring(0, d1);
        String pl = token.substring(d1 + 1, d2);
        String s = token.substring(d2 + 1);
        if (h.isEmpty()) {
            throw new SsoException("jose.malformed");
        }
        if (s.isEmpty()) {
            throw new SsoException("jose.signature_missing");
        }
        Map<String, Object> header = decodeHeader(h);
        Object alg = header.get("alg");
        if (!(alg instanceof String)) {
            throw new SsoException("jose.alg_missing");
        }
        if ("none".equalsIgnoreCase(((String) alg).strip())) {
            throw new SsoException("jose.alg_none");
        }
        checkHeaderPolicy(header);
        return new Parts(h, pl, header, decode(pl), decode(s));
    }

    static void checkHeaderPolicy(Map<String, Object> header) {
        for (String f : FORBIDDEN_HEADERS) {
            if (header.containsKey(f)) {
                throw new SsoException("jose.header_forbidden");
            }
        }
        if (header.containsKey("crit")) {
            throw new SsoException("jose.crit_unsupported");
        }
        if (header.containsKey("zip")) {
            throw new SsoException("jose.zip_unsupported");
        }
    }

    static Map<String, Object> decodeHeader(String b64) {
        byte[] raw = decode(b64);
        try {
            return Json.parseObject(raw, HEADER_LIMITS);
        } catch (SsoException e) {
            throw new SsoException("jose.header_invalid");
        }
    }

    static byte[] decode(String b64) {
        try {
            return Bytes.b64urlDecode(b64);
        } catch (IllegalArgumentException e) {
            throw new SsoException("jose.bad_base64");
        }
    }
}
