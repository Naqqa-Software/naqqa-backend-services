package com.naqqa.sso.vectors;

import com.naqqa.sso.json.Json;
import com.naqqa.sso.password.PasswordHasher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static com.naqqa.sso.vectors.VectorIo.m;

final class PasswordVectors {
    private static final HexFormat HEX = HexFormat.of();
    private static final String PEPPER = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
    private static final String OTHER_PEPPER = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";
    private final Map<String, PasswordHasher> hashers = new HashMap<>();
    private final Map<String, Map<String, Object>> prior;
    private final List<Object> cases = new ArrayList<>();

    PasswordVectors(Map<String, Object> existing) {
        prior = VectorIo.casesByName(existing);
    }

    private PasswordHasher hasher(String pepperHex, long iterations) {
        return hashers.computeIfAbsent(pepperHex + ":" + iterations, k -> new PasswordHasher(HEX.parseHex(pepperHex), (int) iterations));
    }

    static Map<String, Object> evaluate(Map<String, Object> in, PasswordHasher h) {
        String hash = (String) in.get("hash");
        Map<String, Object> out = m("verifies", h.verify((String) in.get("password"), hash), "needsRehash", h.needsRehash(hash));
        try {
            PasswordHasher.Parsed p = PasswordHasher.parse(hash);
            out.put("parsed", m("iterations", (long) p.iterations(), "saltHex", HEX.formatHex(p.salt()), "hashHex", HEX.formatHex(p.hash())));
        } catch (com.naqqa.sso.SsoException e) {
            out.put("parsed", null);
            out.put("parseError", e.code());
        }
        return out;
    }

    private String create(String name, String password, String pepperHex, long iterations) {
        if (prior.containsKey(name)) {
            return (String) Json.asObject(prior.get(name).get("input")).get("hash");
        }
        return hasher(pepperHex, iterations).hash(password);
    }

    private void add(String name, String description, boolean testOnly, String password, String pepperHex, long policyIterations,
                     String hash, boolean wantVerifies, boolean wantRehash) {
        Map<String, Object> in = m("password", password, "pepperHex", pepperHex, "policyIterations", policyIterations, "hash", hash);
        Map<String, Object> out = evaluate(in, hasher(pepperHex, policyIterations));
        if (!out.get("verifies").equals(wantVerifies) || !out.get("needsRehash").equals(wantRehash)) {
            throw new AssertionError("password vector " + name + " has the wrong outcome " + out);
        }
        Map<String, Object> c = VectorIo.docCase(name, description, in, out);
        if (testOnly) {
            c.put("testOnly", true);
        }
        cases.add(c);
    }

    private static String mutate(String hash, String from, String to) {
        if (!hash.contains(from)) {
            throw new IllegalStateException(from);
        }
        return hash.replace(from, to);
    }

    Map<String, Object> build() {
        String pw = "correct horse battery staple 9!";
        String composed = "Café Über 123 pass";
        String decomposed = "Café Über 123 pass";
        String h210 = create("verify-default-iterations", pw, PEPPER, 210_000);
        add("verify-default-iterations", "Hash at the default 210000 iterations, policy 210000.", false, pw, PEPPER, 210_000, h210, true, false);
        add("wrong-password", "Same hash, different password.", false, pw + "x", PEPPER, 210_000, h210, false, false);
        add("wrong-pepper", "Same hash, different pepper.", false, pw, OTHER_PEPPER, 210_000, h210, false, false);
        add("needs-rehash-policy-raised", "Hash at 210000 while policy is 300000, verifies and asks for rehash.", false, pw, PEPPER, 300_000, h210, true, true);
        String h100 = create("low-iterations-100k", pw, PEPPER, 100_000);
        add("low-iterations-100k", "TEST ONLY: hash at the minimum 100000 iterations, policy 210000.", true, pw, PEPPER, 210_000, h100, true, true);
        add("low-iterations-policy-equal", "TEST ONLY: hash at 100000 and policy 100000, no rehash.", true, pw, PEPPER, 100_000, h100, true, false);
        String hNfkc = create("unicode-normalization", composed, PEPPER, 100_000);
        add("unicode-normalization", "TEST ONLY: hashed from the composed form, verified with the decomposed form (NFKC).", true, decomposed, PEPPER, 100_000, hNfkc, true, false);
        add("empty-password", "Empty password never verifies.", true, "", PEPPER, 100_000, h100, false, false);
        add("malformed-version-2", "Version v=2 is unknown: no verify, rehash needed.", true, pw, PEPPER, 100_000, mutate(h100, "$v=1$", "$v=2$"), false, true);
        add("malformed-wrong-id", "Another algorithm id.", true, pw, PEPPER, 100_000, mutate(h100, "$pbkdf2-sha512$", "$argon2id$"), false, true);
        add("malformed-iterations-too-low", "Iterations below the 100000 floor are refused.", true, pw, PEPPER, 100_000, mutate(h100, "$i=100000$", "$i=1000$"), false, true);
        add("malformed-iterations-leading-zero", "Iterations written with a leading zero.", true, pw, PEPPER, 100_000, mutate(h100, "$i=100000$", "$i=0100000$"), false, true);
        add("malformed-padded-base64", "Salt with = padding is not canonical.", true, pw, PEPPER, 100_000, padSalt(h100), false, true);
        add("malformed-truncated", "Hash truncated by one character.", true, pw, PEPPER, 100_000, h100.substring(0, h100.length() - 1), false, true);
        add("malformed-empty", "Empty string.", true, pw, PEPPER, 100_000, "", false, true);
        return m("description", "PBKDF2-SHA512 PHC strings: $pbkdf2-sha512$v=1$i=<iterations>$<salt b64 no pad>$<hash b64 no pad>. "
                        + "Derivation: pre = HMAC-SHA256(pepper, UTF-8(NFKC(password))); key = PBKDF2-HMAC-SHA512(password = ASCII(base64url(pre) without padding), salt, iterations, 64 bytes). "
                        + "Cases marked testOnly use low iteration counts and a public pepper and must never be used outside tests.",
                "version", 1, "cases", cases);
    }

    private static String padSalt(String hash) {
        String[] p = hash.split("\\$", -1);
        p[4] = p[4] + "==";
        return String.join("$", p);
    }
}
