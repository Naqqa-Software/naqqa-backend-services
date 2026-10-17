package com.naqqa.sso.session;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

public final class CsrfTokens {
    private final byte[] key;

    public CsrfTokens(byte[] key) {
        if (key == null || key.length < 32) {
            throw new SsoException("csrf_key_invalid");
        }
        this.key = key.clone();
    }

    private String sign(String sessionId, String nonce) {
        return Bytes.b64url(Hashes.hmacSha256(key, Bytes.utf8("csrf|" + sessionId + "|" + nonce)));
    }

    public String issue(String sessionId) {
        String nonce = Bytes.randomToken(16);
        return nonce + "." + sign(sessionId, nonce);
    }

    public boolean verify(String sessionId, String token) {
        if (sessionId == null || token == null) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return false;
        }
        String nonce = token.substring(0, dot);
        return Bytes.constantTimeEquals(sign(sessionId, nonce), token.substring(dot + 1));
    }

    public boolean verifyDoubleSubmit(String sessionId, String cookieToken, String headerToken) {
        return cookieToken != null && headerToken != null
                && Bytes.constantTimeEquals(cookieToken, headerToken)
                && verify(sessionId, headerToken);
    }
}
