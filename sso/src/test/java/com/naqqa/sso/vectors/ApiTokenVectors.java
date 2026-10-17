package com.naqqa.sso.vectors;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.tokens.ApiToken;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.naqqa.sso.vectors.VectorIo.m;

final class ApiTokenVectors {
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private ApiTokenVectors() {
    }

    private static String fill(int length, int seed) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt((i * 7 + seed * 13 + 3) % 62));
        }
        return sb.toString();
    }

    static Map<String, Object> evaluate(String token) {
        try {
            ApiToken t = ApiToken.parse(token);
            return m("valid", true, "kind", t.kind().code(), "env", t.env().code(), "id", t.id(), "secret", t.secret());
        } catch (SsoException e) {
            return m("valid", false, "error", e.code());
        }
    }

    private static String tamperCrc(String token) {
        char last = token.charAt(token.length() - 1);
        return token.substring(0, token.length() - 1) + (last == 'a' ? 'b' : 'a');
    }

    static Map<String, Object> build() {
        String id = fill(16, 1);
        String secret = fill(43, 2);
        String skLive = new ApiToken(ApiTokenKind.SECRET, ApiTokenEnv.LIVE, id, secret).encode();
        String skTest = new ApiToken(ApiTokenKind.SECRET, ApiTokenEnv.TEST, fill(16, 3), fill(43, 4)).encode();
        String pkLive = new ApiToken(ApiTokenKind.PUBLISHABLE, ApiTokenEnv.LIVE, fill(16, 5), fill(43, 6)).encode();
        String pkTest = new ApiToken(ApiTokenKind.PUBLISHABLE, ApiTokenEnv.TEST, fill(16, 7), fill(43, 8)).encode();
        String body = skLive.substring(0, skLive.lastIndexOf('_'));
        Object[][] rows = {
                {"valid-secret-live", "Secret key, live environment.", skLive, true},
                {"valid-secret-test", "Secret key, test environment.", skTest, true},
                {"valid-publishable-live", "Publishable key, live environment.", pkLive, true},
                {"valid-publishable-test", "Publishable key, test environment.", pkTest, true},
                {"bad-crc", "Last checksum character changed.", tamperCrc(skLive), false},
                {"bad-crc-body-changed", "A body character changed, checksum no longer matches.", skLive.replaceFirst(secret.substring(0, 4), "0000"), false},
                {"bad-prefix", "Prefix ssx instead of sso.", "ssx" + skLive.substring(3), false},
                {"bad-prefix-uppercase", "Prefix SSO is case sensitive.", "SSO" + skLive.substring(3), false},
                {"wrong-env", "Environment prod is unknown.", skLive.replace("_live_", "_prod_"), false},
                {"wrong-kind", "Kind ak is unknown.", skLive.replace("_sk_", "_ak_"), false},
                {"secret-too-short", "Secret has 42 characters.", body.substring(0, body.length() - 1) + "_AAAAAA", false},
                {"illegal-character-in-secret", "Dash inside the secret.", skLive.substring(0, 40) + "-" + skLive.substring(41), false},
                {"extra-segment", "Extra underscore segment.", skLive + "_x", false},
                {"leading-bearer", "Authorization scheme must be stripped before parsing.", "Bearer " + skLive, false},
                {"trailing-space", "Trailing whitespace.", skLive + " ", false},
                {"empty", "Empty string.", "", false},
        };
        List<Object> cases = new ArrayList<>();
        for (Object[] r : rows) {
            String token = (String) r[2];
            Map<String, Object> out = evaluate(token);
            if (!out.get("valid").equals(r[3])) {
                throw new AssertionError("api-token vector " + r[0] + " has the wrong outcome " + out);
            }
            if (out.containsKey("secret")) {
                out.put("secret", java.util.Base64.getEncoder().encodeToString(((String) out.get("secret")).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            }
            cases.add(VectorIo.docCase((String) r[0], (String) r[1],
                    m("tokenBase64", java.util.Base64.getEncoder().encodeToString(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII))), out));
        }
        return m("description", "Token format: sso_<kind>_<env>_<id16>_<secret43>_<crc6>. Kind pk or sk, env live or test, id and secret Base62. "
                        + "crc is CRC-32 (IEEE, as unsigned integer) of the ASCII text before the last underscore, written as 6 Base62 digits, most significant first, "
                        + "alphabet 0-9A-Za-z. Total length is 91. input.tokenBase64 and expected.secret are standard Base64 of the ASCII text, "
                        + "so that secret scanners do not mistake these test vectors for live keys.",
                "version", 1, "length", (long) ApiToken.TOKEN_LENGTH, "cases", cases);
    }
}
