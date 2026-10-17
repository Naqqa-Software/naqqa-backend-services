package com.naqqa.sso.jose;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.json.JsonLimits;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Jwt {
    public static final String TYP_JWT = "JWT";
    public static final String TYP_ACCESS_TOKEN = "at+jwt";
    private static final JsonLimits CLAIM_LIMITS = new JsonLimits(16, Jws.MAX_TOKEN_SIZE);

    private Jwt() {
    }

    public record Verified(Map<String, Object> header, JwtClaims claims, String kid) {
    }

    public static String sign(KeyRing ring, String typ, Map<String, Object> claims) {
        return sign(ring.active(), typ, claims);
    }

    public static String sign(SigningKey key, String typ, Map<String, Object> claims) {
        Map<String, Object> header = new LinkedHashMap<>();
        if (typ != null) {
            header.put("typ", typ);
        }
        return Jws.sign(key, header, Json.writeUtf8(claims));
    }

    static Verified fromJws(Jws.Verified jws) {
        Map<String, Object> claims;
        try {
            claims = Json.parseObject(jws.payload(), CLAIM_LIMITS);
        } catch (SsoException e) {
            throw new SsoException("jwt.claims_invalid");
        }
        return new Verified(jws.header(), new JwtClaims(claims), jws.kid());
    }

    public static Verified verify(String token, KeyRing ring, JwtValidator validator) {
        Verified v = fromJws(Jws.verify(token, ring));
        validator.validate(v.header(), v.claims());
        return v;
    }

    public static Verified verify(String token, SigningKey key, JwtValidator validator) {
        Verified v = fromJws(Jws.verify(token, key));
        validator.validate(v.header(), v.claims());
        return v;
    }
}
