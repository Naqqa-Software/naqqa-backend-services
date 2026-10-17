package com.naqqa.sso.jose;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.Jwk;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.json.JsonLimits;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Jwks {
    public static final int MAX_KEYS = 100;
    public static final JsonLimits LIMITS = new JsonLimits(8, 256 * 1024);
    private static final Set<String> SUPPORTED_KTY = Set.of("RSA", "EC", "OKP");
    private static final Set<String> SUPPORTED_CRV = Set.of("P-256", "Ed25519");

    private Jwks() {
    }

    public static Map<String, Object> publishMap(KeyRing ring) {
        List<Object> keys = new ArrayList<>();
        for (SigningKey k : ring.verificationKeys()) {
            keys.add(Jwk.toPublicJwk(k.publicOnly()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keys", keys);
        return out;
    }

    public static String publish(KeyRing ring) {
        return Json.write(publishMap(ring));
    }

    public static List<SigningKey> parse(String json) {
        Map<String, Object> root;
        try {
            root = Json.parseObject(json, LIMITS);
        } catch (SsoException e) {
            throw new SsoException("jwks.invalid");
        }
        Object keysValue = root.get("keys");
        if (!(keysValue instanceof List)) {
            throw new SsoException("jwks.invalid");
        }
        List<?> keys = (List<?>) keysValue;
        if (keys.size() > MAX_KEYS) {
            throw new SsoException("jwks.too_many_keys");
        }
        List<SigningKey> out = new ArrayList<>();
        Set<String> kids = new HashSet<>();
        for (Object o : keys) {
            if (!(o instanceof Map)) {
                throw new SsoException("jwks.invalid");
            }
            Map<String, Object> jwk = Json.asObject(o);
            if (Jwk.hasPrivateMembers(jwk)) {
                throw new SsoException("jwks.private_material");
            }
            if (!supported(jwk)) {
                continue;
            }
            SigningKey key = Jwk.parse(jwk);
            if (!kids.add(key.kid())) {
                throw new SsoException("jwks.duplicate_kid");
            }
            out.add(key);
        }
        return out;
    }

    public static KeyRing toKeyRing(String json, Clock clock) {
        return KeyRing.verifyOnly(clock, parse(json));
    }

    private static boolean supported(Map<String, Object> jwk) {
        Object kty = jwk.get("kty");
        if (!(kty instanceof String) || !SUPPORTED_KTY.contains(kty)) {
            return false;
        }
        Object use = jwk.get("use");
        if (use != null && !"sig".equals(use)) {
            return false;
        }
        Object crv = jwk.get("crv");
        if (!"RSA".equals(kty) && (!(crv instanceof String) || !SUPPORTED_CRV.contains(crv))) {
            return false;
        }
        Object alg = jwk.get("alg");
        if (alg != null) {
            if (!(alg instanceof String)) {
                return false;
            }
            for (SigningAlgorithm a : SigningAlgorithm.values()) {
                if (a.jwa().equals(alg)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }
}
