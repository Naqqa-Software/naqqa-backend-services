package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;

public enum CoseAlgorithm {
    ES256(-7, 2, "SHA256withECDSA"),
    EDDSA(-8, 1, "Ed25519"),
    RS256(-257, 3, "SHA256withRSA"),
    PS256(-37, 3, "RSASSA-PSS");

    private final int id;
    private final int keyType;
    private final String jcaName;

    CoseAlgorithm(int id, int keyType, String jcaName) {
        this.id = id;
        this.keyType = keyType;
        this.jcaName = jcaName;
    }

    public int id() {
        return id;
    }

    public int keyType() {
        return keyType;
    }

    public static CoseAlgorithm of(long id) {
        for (CoseAlgorithm a : values()) {
            if (a.id == id) {
                return a;
            }
        }
        throw new SsoException("webauthn.cose.alg_unsupported");
    }

    public boolean verify(PublicKey key, byte[] data, byte[] signature) {
        if (key == null || data == null || signature == null || signature.length == 0) {
            return false;
        }
        try {
            Signature s = Signature.getInstance(jcaName);
            if (this == PS256) {
                s.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
            }
            s.initVerify(key);
            s.update(data);
            return s.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }
}
