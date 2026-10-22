package com.naqqa.sso.vectors;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.Jwk;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.jose.Jwt;
import com.naqqa.sso.jose.JwtValidator;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.naqqa.sso.vectors.VectorIo.m;

final class JwtVectors {
    static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");
    static final String ISSUER = "https://sso.naqqa.com";
    static final String AUDIENCE = "api.naqqa.com";
    static final String TYP = "at+jwt";
    static final long LEEWAY = 60;
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private final Map<String, SigningKey> keys = new LinkedHashMap<>();
    private final List<Object> keyDocs = new ArrayList<>();
    private final KeyRing ring;
    private final JwtValidator validator;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final List<Object> cases = new ArrayList<>();
    private final Map<String, Map<String, Object>> prior;

    JwtVectors(Map<String, Object> existing) {
        prior = VectorIo.casesByName(existing);
        Map<String, Map<String, Object>> priorKeys = new LinkedHashMap<>();
        if (existing != null) {
            for (Object o : Json.asList(existing.get("keys"))) {
                Map<String, Object> k = Json.asObject(o);
                priorKeys.put((String) k.get("name"), k);
            }
        }
        SigningAlgorithm[] algs = {SigningAlgorithm.RS256, SigningAlgorithm.ES256, SigningAlgorithm.EdDSA};
        String[] names = {"rs256", "es256", "eddsa"};
        List<SigningKey> verification = new ArrayList<>();
        for (int i = 0; i < algs.length; i++) {
            Map<String, Object> doc = priorKeys.get(names[i]);
            SigningKey key = doc != null ? Jwk.parse(Json.asObject(doc.get("privateJwk"))) : SigningKey.generate(algs[i]);
            keys.put(names[i], key);
            verification.add(key.publicOnly());
            keyDocs.add(m("name", names[i], "alg", algs[i].jwa(), "kid", key.kid(), "privateJwk", Jwk.toPrivateJwk(key),
                    "publicJwk", Jwk.toPublicJwk(key.publicOnly())));
        }
        ring = KeyRing.verifyOnly(clock, verification);
        validator = JwtValidator.builder(clock).issuer(ISSUER).audience(AUDIENCE).type(TYP).leeway(Duration.ofSeconds(LEEWAY)).build();
    }

    SigningKey key(String name) {
        return keys.get(name);
    }

    List<Object> keyDocs() {
        return keyDocs;
    }

    Map<String, Object> claims(long exp) {
        return m("iss", ISSUER, "sub", "user-7f3a91", "aud", AUDIENCE, "exp", exp, "iat", NOW.getEpochSecond() - 300,
                "jti", "9b2f6c1e-4d7a-4e51-8a30-5c1d2e7f9a10", "client_id", "naqqa-web", "scope", "openid profile",
                "roles", List.of("admin", "billing"));
    }

    Map<String, Object> claims() {
        return claims(NOW.getEpochSecond() + 600);
    }

    Map<String, Object> evaluate(String token) {
        try {
            Jwt.Verified v = validator.verify(token, ring);
            return m("valid", true, "alg", v.header().get("alg"), "kid", v.kid(), "typ", v.header().get("typ"),
                    "claims", Json.parseObject(Json.write(v.claims().values())));
        } catch (SsoException e) {
            return m("valid", false, "error", e.code());
        }
    }

    private void add(String name, String description, String wantError, String token) {
        Map<String, Object> in = prior.containsKey(name) ? Json.asObject(prior.get(name).get("input")) : m("token", token);
        Map<String, Object> out = evaluate((String) in.get("token"));
        String got = Boolean.TRUE.equals(out.get("valid")) ? null : (String) out.get("error");
        if (!java.util.Objects.equals(wantError, got)) {
            throw new AssertionError("jwt vector " + name + " intended " + wantError + " but implementation gave " + got);
        }
        cases.add(VectorIo.docCase(name, description, in, out));
    }

    private static String b64(Object json) {
        return Bytes.b64url(Json.writeUtf8(json));
    }

    private String signRaw(Map<String, Object> header, Object claims, SigningKey signer) {
        String input = b64(header) + "." + b64(claims);
        return input + "." + Bytes.b64url(signer.sign(input.getBytes(StandardCharsets.US_ASCII)));
    }

    private Map<String, Object> header(String alg, String kid, String typ) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("alg", alg);
        if (kid != null) {
            h.put("kid", kid);
        }
        if (typ != null) {
            h.put("typ", typ);
        }
        return h;
    }

    private static String replaceChar(String s, int idx) {
        char c = s.charAt(idx);
        char r = c == 'A' ? 'B' : 'A';
        return s.substring(0, idx) + r + s.substring(idx + 1);
    }

    private static String nonCanonicalLast(String segment) {
        char last = segment.charAt(segment.length() - 1);
        int idx = B64.indexOf(last);
        if (segment.length() % 4 == 0 || (idx & 1) != 0) {
            throw new IllegalStateException("no slack bits");
        }
        return segment.substring(0, segment.length() - 1) + B64.charAt(idx | 1);
    }

    Map<String, Object> build() {
        long nowS = NOW.getEpochSecond();
        for (String n : keys.keySet()) {
            SigningKey k = keys.get(n);
            add("valid-" + n, "Access token signed with " + k.alg().jwa() + ", typ at+jwt, all claims valid.", null,
                    Jwt.sign(k, TYP, claims()));
        }
        SigningKey rs = keys.get("rs256");
        SigningKey es = keys.get("es256");
        SigningKey ed = keys.get("eddsa");
        Map<String, Object> multiAud = claims();
        multiAud.put("aud", List.of("other.naqqa.com", AUDIENCE));
        add("valid-audience-array", "aud is an array that contains the expected audience.", null, Jwt.sign(ed, TYP, multiAud));
        add("valid-typ-media-type", "typ given as application/at+jwt is accepted (RFC 8725 section 3.11).", null,
                Jwt.sign(es, "application/at+jwt", claims()));
        add("valid-within-leeway", "exp is 30 seconds in the past, inside the 60 second leeway.", null,
                Jwt.sign(rs, TYP, claims(nowS - 30)));
        add("expired-at-leeway-edge", "exp plus leeway equals the clock, token is expired.", "jwt.expired",
                Jwt.sign(rs, TYP, claims(nowS - LEEWAY)));
        add("expired-long-ago", "exp one hour in the past.", "jwt.expired", Jwt.sign(es, TYP, claims(nowS - 3600)));
        Map<String, Object> nbf = claims();
        nbf.put("nbf", nowS + 120);
        add("not-yet-valid", "nbf 120 seconds in the future, beyond leeway.", "jwt.not_yet_valid", Jwt.sign(ed, TYP, nbf));
        Map<String, Object> iatFuture = claims();
        iatFuture.put("iat", nowS + 300);
        add("iat-in-future", "iat 300 seconds in the future, beyond leeway.", "jwt.iat_future", Jwt.sign(rs, TYP, iatFuture));
        Map<String, Object> wrongAud = claims();
        wrongAud.put("aud", "api.other.example");
        add("wrong-audience", "aud does not contain the expected audience.", "jwt.audience_mismatch", Jwt.sign(rs, TYP, wrongAud));
        Map<String, Object> wrongIss = claims();
        wrongIss.put("iss", "https://evil.example.com");
        add("wrong-issuer", "iss differs from the expected issuer.", "jwt.issuer_mismatch", Jwt.sign(es, TYP, wrongIss));
        add("wrong-typ", "typ is JWT, not at+jwt (access token type confusion).", "jwt.typ_mismatch", Jwt.sign(rs, "JWT", claims()));
        add("missing-typ", "No typ header at all.", "jwt.typ_mismatch", Jwt.sign(ed, null, claims()));
        Map<String, Object> noExp = claims();
        noExp.remove("exp");
        add("missing-exp", "exp claim absent.", "jwt.exp_missing", Jwt.sign(rs, TYP, noExp));

        String rsKid = rs.kid();
        String fakeSig = Bytes.b64url(new byte[64]);
        add("alg-none", "alg none with a non-empty signature segment.", "jose.alg_none",
                b64(header("none", rsKid, TYP)) + "." + b64(claims()) + "." + fakeSig);
        add("alg-none-empty-signature", "alg none with the classic empty signature segment.", "jose.signature_missing",
                b64(header("none", rsKid, TYP)) + "." + b64(claims()) + ".");
        add("alg-none-mixed-case", "alg NoNe, case variants must also be rejected.", "jose.alg_none",
                b64(header("NoNe", rsKid, TYP)) + "." + b64(claims()) + "." + fakeSig);
        String hsInput = b64(header("HS256", rsKid, TYP)) + "." + b64(claims());
        byte[] hmacKey = Json.write(Jwk.toPublicJwk(rs.publicOnly())).getBytes(StandardCharsets.UTF_8);
        add("alg-switch-hs256-public-key-as-secret", "alg switched to HS256, MAC computed with the public key as secret.",
                "jose.alg_mismatch", hsInput + "." + Bytes.b64url(Hashes.hmacSha256(hmacKey, hsInput.getBytes(StandardCharsets.US_ASCII))));
        add("alg-switch-rs256-key-claims-es256", "Header says ES256 but kid points at the RS256 key.", "jose.alg_mismatch",
                signRaw(header("ES256", rsKid, TYP), claims(), rs));
        add("alg-switch-eddsa-key-claims-rs256", "Header says RS256 but kid points at the Ed25519 key.", "jose.alg_mismatch",
                signRaw(header("RS256", ed.kid(), TYP), claims(), ed));

        String good = Jwt.sign(rs, TYP, claims());
        String[] parts = good.split("\\.");
        Map<String, Object> tamperedClaims = claims();
        tamperedClaims.put("sub", "admin");
        add("tampered-payload", "Payload replaced with different claims, original signature kept.", "jose.signature_invalid",
                parts[0] + "." + b64(tamperedClaims) + "." + parts[2]);
        add("tampered-signature", "One character of the signature changed.", "jose.signature_invalid",
                parts[0] + "." + parts[1] + "." + replaceChar(parts[2], 10));
        add("signature-from-other-key", "Signature produced by the ES256 key but header and kid claim the RS256 key.",
                "jose.signature_invalid", signRaw(header("RS256", rsKid, TYP), claims(), es));
        String esGood = Jwt.sign(es, TYP, claims());
        String[] esParts = esGood.split("\\.");
        add("noncanonical-signature-base64url", "Last signature character changed in its unused bits, same bytes decoded.",
                "jose.bad_base64", esParts[0] + "." + esParts[1] + "." + nonCanonicalLast(esParts[2]));
        add("padded-payload-base64url", "Payload segment carries = padding.", "jose.bad_base64",
                parts[0] + "." + parts[1] + "=." + parts[2]);
        add("trailing-whitespace", "Token followed by a newline.", "jose.bad_base64", good + "\n");
        add("unknown-kid", "kid not present in the key set.", "jose.kid_unknown", signRaw(header("RS256", "no-such-key", TYP), claims(), rs));
        add("missing-kid", "No kid header, key lookup is by kid only.", "jose.kid_missing", signRaw(header("RS256", null, TYP), claims(), rs));
        Map<String, Object> critHeader = header("RS256", rsKid, TYP);
        critHeader.put("crit", List.of("exp"));
        add("header-crit", "Unsupported crit header.", "jose.crit_unsupported", signRaw(critHeader, claims(), rs));
        Map<String, Object> jwkHeader = header("ES256", es.kid(), TYP);
        jwkHeader.put("jwk", Jwk.toPublicJwk(es.publicOnly()));
        add("header-embedded-jwk", "Embedded jwk header must be rejected.", "jose.header_forbidden", signRaw(jwkHeader, claims(), es));
        Map<String, Object> jkuHeader = header("RS256", rsKid, TYP);
        jkuHeader.put("jku", "https://evil.example.com/jwks.json");
        add("header-jku", "jku header must be rejected.", "jose.header_forbidden", signRaw(jkuHeader, claims(), rs));
        add("malformed-two-segments", "Only two segments.", "jose.malformed", parts[0] + "." + parts[1]);
        add("malformed-four-segments", "Four segments.", "jose.malformed", good + ".AAAA");
        add("malformed-empty", "Empty string.", "jose.malformed", "");
        add("empty-signature", "Signature segment empty on a real header and payload.", "jose.signature_missing",
                parts[0] + "." + parts[1] + ".");

        return m("description", "Signed access tokens (RS256, ES256, EdDSA) and negative cases. Keys are test-only and committed on purpose.",
                "version", 1,
                "validator", m("issuer", ISSUER, "audience", AUDIENCE, "typ", TYP, "requireExp", true, "leewaySeconds", LEEWAY,
                        "nowEpochSeconds", NOW.getEpochSecond(), "nowIso", NOW.toString()),
                "keys", keyDocs,
                "cases", cases);
    }
}
