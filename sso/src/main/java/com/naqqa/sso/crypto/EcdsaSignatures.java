package com.naqqa.sso.crypto;

import com.naqqa.sso.SsoException;

import java.math.BigInteger;
import java.util.Arrays;

public final class EcdsaSignatures {
    public static final int P256_COMPONENT = 32;

    private EcdsaSignatures() {
    }

    public static byte[] derToRaw(byte[] der) {
        if (der == null || der.length < 8 || der.length > 72) {
            throw new SsoException("crypto.ecdsa_der_invalid");
        }
        try {
            Der outer = new Der(der);
            Der seq = outer.read(Der.SEQUENCE).reader();
            outer.finish();
            byte[] r = component(seq.read(Der.INTEGER).value());
            byte[] s = component(seq.read(Der.INTEGER).value());
            seq.finish();
            byte[] out = new byte[2 * P256_COMPONENT];
            System.arraycopy(r, 0, out, P256_COMPONENT - r.length, r.length);
            System.arraycopy(s, 0, out, 2 * P256_COMPONENT - s.length, s.length);
            return out;
        } catch (SsoException e) {
            throw new SsoException("crypto.ecdsa_der_invalid");
        }
    }

    private static byte[] component(byte[] v) {
        if (v.length == 0 || (v[0] & 0x80) != 0) {
            throw new SsoException("crypto.ecdsa_der_invalid");
        }
        if (v.length > 1 && v[0] == 0 && (v[1] & 0x80) == 0) {
            throw new SsoException("crypto.ecdsa_der_invalid");
        }
        byte[] stripped = v[0] == 0 ? Arrays.copyOfRange(v, 1, v.length) : v;
        if (stripped.length == 0 || stripped.length > P256_COMPONENT) {
            throw new SsoException("crypto.ecdsa_der_invalid");
        }
        return stripped;
    }

    public static byte[] rawToDer(byte[] raw) {
        if (raw == null || raw.length != 2 * P256_COMPONENT) {
            throw new SsoException("crypto.ecdsa_raw_invalid");
        }
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(raw, 0, P256_COMPONENT));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(raw, P256_COMPONENT, 2 * P256_COMPONENT));
        BigInteger n = EcCurves.P256.getOrder();
        if (r.signum() == 0 || s.signum() == 0 || r.compareTo(n) >= 0 || s.compareTo(n) >= 0) {
            throw new SsoException("crypto.ecdsa_raw_invalid");
        }
        return Der.encode(Der.SEQUENCE, Der.encode(Der.INTEGER, r.toByteArray()), Der.encode(Der.INTEGER, s.toByteArray()));
    }
}
