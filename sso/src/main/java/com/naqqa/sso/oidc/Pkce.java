package com.naqqa.sso.oidc;

import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.nio.charset.StandardCharsets;

public final class Pkce {
    public static final String S256 = "S256";

    private Pkce() {
    }

    public static boolean validVerifier(String v) {
        if (v == null || v.length() < 43 || v.length() > 128) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    public static boolean validChallenge(String c) {
        if (c == null || c.length() != 43) {
            return false;
        }
        try {
            return Bytes.b64urlDecode(c).length == 32;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String challenge(String verifier) {
        return Bytes.b64url(Hashes.sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    public static boolean verify(String verifier, String challenge) {
        if (!validVerifier(verifier) || challenge == null) {
            return false;
        }
        return Bytes.constantTimeEquals(challenge(verifier), challenge);
    }
}
