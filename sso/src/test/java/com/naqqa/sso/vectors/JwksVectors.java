package com.naqqa.sso.vectors;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.Jwk;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.jose.Jwks;
import com.naqqa.sso.jose.RfcVectors;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.naqqa.sso.vectors.VectorIo.m;

final class JwksVectors {
    private JwksVectors() {
    }

    static String thumbprintInput(Map<String, Object> publicJwk) {
        Map<String, Object> t = new LinkedHashMap<>();
        switch ((String) publicJwk.get("kty")) {
            case "RSA" -> {
                t.put("e", publicJwk.get("e"));
                t.put("kty", "RSA");
                t.put("n", publicJwk.get("n"));
            }
            case "EC" -> {
                t.put("crv", publicJwk.get("crv"));
                t.put("kty", "EC");
                t.put("x", publicJwk.get("x"));
                t.put("y", publicJwk.get("y"));
            }
            default -> {
                t.put("crv", publicJwk.get("crv"));
                t.put("kty", "OKP");
                t.put("x", publicJwk.get("x"));
            }
        }
        return Json.write(t);
    }

    static Map<String, Object> evaluate(Map<String, Object> jwks) {
        try {
            List<Object> out = new ArrayList<>();
            for (SigningKey k : Jwks.parse(Json.write(jwks))) {
                out.add(m("kid", k.kid(), "alg", k.alg().jwa()));
            }
            return m("ok", true, "keys", out);
        } catch (SsoException e) {
            return m("ok", false, "error", e.code());
        }
    }

    private static void parseCase(List<Object> cases, String name, String description, Map<String, Object> jwks, String wantError) {
        Map<String, Object> out = evaluate(jwks);
        String got = Boolean.TRUE.equals(out.get("ok")) ? null : (String) out.get("error");
        if (!java.util.Objects.equals(wantError, got)) {
            throw new AssertionError("jwks vector " + name + " intended " + wantError + " but implementation gave " + got);
        }
        cases.add(VectorIo.docCase(name, description, m("jwks", jwks), out));
    }

    static Map<String, Object> build(JwtVectors jwt) {
        Clock clock = Clock.fixed(JwtVectors.NOW, ZoneOffset.UTC);
        List<SigningKey> pubs = new ArrayList<>();
        List<Object> thumbs = new ArrayList<>();
        List<Object> publicJwks = new ArrayList<>();
        for (String n : List.of("rs256", "es256", "eddsa")) {
            SigningKey k = jwt.key(n);
            pubs.add(k.publicOnly());
            Map<String, Object> pub = Jwk.toPublicJwk(k.publicOnly());
            String input = thumbprintInput(pub);
            String tp = Bytes.b64url(Hashes.sha256(Bytes.utf8(input)));
            if (!tp.equals(k.thumbprint()) || !tp.equals(k.kid())) {
                throw new AssertionError("thumbprint mismatch for " + n);
            }
            thumbs.add(m("name", n, "publicJwk", pub, "thumbprintInput", input, "thumbprint", tp));
            publicJwks.add(pub);
        }
        KeyRing ring = KeyRing.verifyOnly(clock, pubs);
        Map<String, Object> published = Jwks.publishMap(ring);
        Map<String, Object> publishedByKid = new LinkedHashMap<>();
        for (Object o : Json.asList(published.get("keys"))) {
            publishedByKid.put((String) Json.asObject(o).get("kid"), o);
        }
        List<Object> orderedKeys = new ArrayList<>();
        for (Object t : thumbs) {
            orderedKeys.add(publishedByKid.get((String) Json.asObject(t).get("thumbprint")));
        }
        Map<String, Object> publish = m("description", "Output of Jwks.publish for the three test keys. Compare as a set of keys by kid, member order does not matter.",
                "jwks", m("keys", orderedKeys));

        SigningKey rfcRsa = Jwk.parse(RfcVectors.RFC7638_RSA_JWK);
        SigningKey rfcEd = Jwk.parse(RfcVectors.RFC8037_JWK);
        Map<String, Object> rfcRsaPub = Jwk.toPublicJwk(rfcRsa.publicOnly());
        Map<String, Object> rfcEdPub = Jwk.toPublicJwk(rfcEd.publicOnly());
        Map<String, Object> rfc = m("description", "RFC 7638 section 3.1 and RFC 8037 appendix A.3 thumbprints.",
                "cases", List.of(
                        m("name", "rfc7638-3.1-rsa", "jwk", Json.parseObject(RfcVectors.RFC7638_RSA_JWK).entrySet().stream()
                                        .filter(e -> !e.getKey().equals("d")).collect(LinkedHashMap::new, (a, e) -> a.put(e.getKey(), e.getValue()), Map::putAll),
                                "thumbprintInput", thumbprintInput(rfcRsaPub), "thumbprint", rfcRsa.thumbprint()),
                        m("name", "rfc8037-a.3-ed25519", "jwk", m("kty", "OKP", "crv", "Ed25519", "x", rfcEdPub.get("x")),
                                "thumbprintInput", thumbprintInput(rfcEdPub), "thumbprint", rfcEd.thumbprint())));

        Map<String, Object> rsPub = Json.asObject(orderedKeys.get(0));
        Map<String, Object> esPub = Json.asObject(orderedKeys.get(1));
        Map<String, Object> edPub = Json.asObject(orderedKeys.get(2));
        List<Object> cases = new ArrayList<>();
        parseCase(cases, "parse-all-three", "JWKS with RS256, ES256 and EdDSA keys parses to three keys, kid is the RFC 7638 thumbprint.",
                m("keys", orderedKeys), null);
        parseCase(cases, "skip-encryption-key", "A key with use enc is ignored, the rest is kept.",
                m("keys", List.of(m("kty", "EC", "crv", "P-256", "x", esPub.get("x"), "y", esPub.get("y"), "use", "enc", "kid", "enc-1"), rsPub)), null);
        parseCase(cases, "skip-unsupported-curve", "A P-384 key is ignored, the rest is kept.",
                m("keys", List.of(m("kty", "EC", "crv", "P-384", "x", "AAAA", "y", "AAAA", "kid", "p384"), edPub)), null);
        parseCase(cases, "skip-oct-without-secret", "kty oct is ignored.",
                m("keys", List.of(m("kty", "oct", "kid", "sym"), edPub)), null);
        parseCase(cases, "reject-private-material", "A JWKS that contains a private member (d) is rejected.",
                m("keys", List.of(Jwk.toPrivateJwk(jwt.key("es256")))), "jwks.private_material");
        parseCase(cases, "reject-duplicate-kid", "The same kid twice is rejected.", m("keys", List.of(rsPub, rsPub)), "jwks.duplicate_kid");
        parseCase(cases, "reject-keys-not-array", "keys must be an array.", m("keys", "nope"), "jwks.invalid");
        parseCase(cases, "reject-missing-keys", "keys member is required.", m(), "jwks.invalid");
        parseCase(cases, "empty-keys", "An empty key set parses to no keys.", m("keys", List.of()), null);

        return m("description", "JWKS publishing, RFC 7638 thumbprints and JWKS parsing rules.", "version", 1,
                "thumbprints", thumbs, "publish", publish, "rfc", rfc, "cases", cases);
    }
}
