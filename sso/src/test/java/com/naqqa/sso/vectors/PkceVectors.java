package com.naqqa.sso.vectors;

import com.naqqa.sso.oidc.Pkce;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.naqqa.sso.vectors.VectorIo.m;

final class PkceVectors {
    private PkceVectors() {
    }

    private static String repeat(String s, int n) {
        return s.repeat(n);
    }

    static Map<String, Object> evaluate(String verifier, String challenge) {
        boolean valid = Pkce.validVerifier(verifier);
        return m("verifierValid", valid, "challenge", valid ? Pkce.challenge(verifier) : null,
                "challengeWellFormed", Pkce.validChallenge(challenge), "verifies", Pkce.verify(verifier, challenge));
    }

    static Map<String, Object> build() {
        String rfcVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String rfcChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        String min43 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOP";
        String all66 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        String max128 = repeat("A", 64) + repeat("b1-._~", 10) + "xxxx";
        Object[][] rows = {
                {"rfc7636-appendix-b", "RFC 7636 appendix B example.", rfcVerifier, rfcChallenge},
                {"min-length-43", "Shortest allowed verifier (43 characters).", min43, null},
                {"all-unreserved-characters", "Verifier using every unreserved character class.", all66 + all66.substring(0, 20), null},
                {"max-length-128", "Longest allowed verifier (128 characters).", max128, null},
                {"too-short-42", "42 characters is rejected.", min43.substring(1), null},
                {"too-long-129", "129 characters is rejected.", max128 + "A", null},
                {"illegal-plus", "Plus is not an unreserved character.", min43.substring(0, 42) + "+", null},
                {"illegal-space", "Space is rejected.", min43.substring(0, 42) + " ", null},
                {"illegal-non-ascii", "Non ASCII character is rejected.", min43.substring(0, 42) + "é", null},
                {"empty", "Empty verifier.", "", null},
                {"wrong-challenge", "Valid verifier, challenge of another verifier.", rfcVerifier, Pkce.challenge(min43)},
                {"padded-challenge", "Challenge with = padding does not verify.", rfcVerifier, rfcChallenge + "="},
                {"plain-method-not-supported", "plain: the challenge equal to the verifier is rejected (S256 only).", rfcVerifier, rfcVerifier},
        };
        List<Object> cases = new ArrayList<>();
        for (Object[] r : rows) {
            String verifier = (String) r[2];
            String challenge = r[3] != null ? (String) r[3] : (Pkce.validVerifier(verifier) ? Pkce.challenge(verifier) : rfcChallenge);
            cases.add(VectorIo.docCase((String) r[0], (String) r[1], m("verifier", verifier, "challenge", challenge), evaluate(verifier, challenge)));
        }
        return m("description", "PKCE S256: challenge = base64url(SHA-256(ASCII(verifier))), no padding. Verifier is 43 to 128 unreserved characters.",
                "version", 1, "method", "S256", "cases", cases);
    }
}
