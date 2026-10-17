package com.naqqa.sso.oidc;

import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

final class Hashing {
    private Hashing() {
    }

    static String token(String value) {
        return Bytes.b64url(Hashes.sha256(Bytes.utf8(value)));
    }

    static String sid(String sessionIdHash) {
        byte[] h = Hashes.sha256(Bytes.utf8("naqqa-oidc-sid|" + sessionIdHash));
        byte[] out = new byte[16];
        System.arraycopy(h, 0, out, 0, 16);
        return Bytes.b64url(out);
    }
}
