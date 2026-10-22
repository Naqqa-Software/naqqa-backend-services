package com.naqqa.sso.crypto;

import com.naqqa.sso.SsoException;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.interfaces.ECKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;

public final class EcCurves {
    public static final ECParameterSpec P256 = load();

    private EcCurves() {
    }

    private static ECParameterSpec load() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isP256(ECParameterSpec spec) {
        if (spec == null) {
            return false;
        }
        return spec.getCurve().equals(P256.getCurve())
                && spec.getGenerator().equals(P256.getGenerator())
                && spec.getOrder().equals(P256.getOrder())
                && spec.getCofactor() == P256.getCofactor();
    }

    public static void requireP256(ECKey key) {
        if (!isP256(key.getParams())) {
            throw new SsoException("crypto.curve_unsupported");
        }
    }

    public static void requireOnCurve(ECPoint point) {
        if (point == null || ECPoint.POINT_INFINITY.equals(point)) {
            throw new SsoException("crypto.point_invalid");
        }
        BigInteger p = ((ECFieldFp) P256.getCurve().getField()).getP();
        BigInteger x = point.getAffineX();
        BigInteger y = point.getAffineY();
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            throw new SsoException("crypto.point_invalid");
        }
        BigInteger a = P256.getCurve().getA();
        BigInteger b = P256.getCurve().getB();
        BigInteger lhs = y.multiply(y).mod(p);
        BigInteger rhs = x.multiply(x).multiply(x).add(a.multiply(x)).add(b).mod(p);
        if (!lhs.equals(rhs)) {
            throw new SsoException("crypto.point_invalid");
        }
    }
}
