package com.naqqa.sso.p2support;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.mfa.SecretSealer;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

public final class MemSealer implements SecretSealer {
    private final byte[] key = new byte[32];

    public MemSealer() {
        new SecureRandom().nextBytes(key);
    }

    @Override
    public byte[] seal(byte[] plaintext, byte[] aad) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(aad);
            byte[] ct = c.doFinal(plaintext);
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public byte[] open(byte[] sealed, byte[] aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, sealed, 0, 12));
            c.updateAAD(aad);
            return c.doFinal(sealed, 12, sealed.length - 12);
        } catch (GeneralSecurityException e) {
            throw new SsoException("seal_open_failed");
        }
    }
}
