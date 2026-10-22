package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record CoseKey(CoseAlgorithm algorithm, PublicKey publicKey, byte[] encoded) {
    public static final int KTY_OKP = 1;
    public static final int KTY_EC2 = 2;
    public static final int KTY_RSA = 3;
    public static final int CRV_P256 = 1;
    public static final int CRV_ED25519 = 6;
    private static final int MIN_RSA_BITS = 2048;
    private static final int MAX_RSA_BITS = 4096;
    private static final byte[] ED25519_SPKI_PREFIX = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
    private static final ECParameterSpec P256 = p256();

    public CoseKey {
        if (algorithm == null || publicKey == null || encoded == null) {
            throw new SsoException("webauthn.cose.invalid");
        }
        encoded = encoded.clone();
    }

    @Override
    public byte[] encoded() {
        return encoded.clone();
    }

    public boolean verify(byte[] data, byte[] signature) {
        return algorithm.verify(publicKey, data, signature);
    }

    public static CoseKey decode(byte[] cbor) {
        Object value = Cbor.decode(cbor);
        return fromMap(Cbor.asMap(value, "webauthn.cose.invalid"), cbor);
    }

    public static CoseKey fromMap(Map<Object, Object> m, byte[] encoded) {
        Object ktyObj = m.get(1L);
        Object algObj = m.get(3L);
        if (!(ktyObj instanceof Long kty) || !(algObj instanceof Long algId)) {
            throw new SsoException("webauthn.cose.invalid");
        }
        if (m.containsKey(-4L)) {
            throw new SsoException("webauthn.cose.private_key");
        }
        CoseAlgorithm alg = CoseAlgorithm.of(algId);
        if (alg.keyType() != kty) {
            throw new SsoException("webauthn.cose.kty_mismatch");
        }
        PublicKey key = switch (alg) {
            case ES256 -> ec2(m);
            case EDDSA -> okp(m);
            case RS256, PS256 -> rsa(m);
        };
        return new CoseKey(alg, key, encoded);
    }

    private static void allowedLabels(Map<Object, Object> m, Set<Long> allowed) {
        for (Object k : m.keySet()) {
            if (!(k instanceof Long l) || !allowed.contains(l)) {
                throw new SsoException("webauthn.cose.unknown_param");
            }
        }
    }

    private static byte[] bytes(Map<Object, Object> m, long label) {
        Object v = m.get(label);
        if (v instanceof byte[] b) {
            return b;
        }
        throw new SsoException("webauthn.cose.invalid");
    }

    private static PublicKey ec2(Map<Object, Object> m) {
        allowedLabels(m, Set.of(1L, 2L, 3L, -1L, -2L, -3L));
        if (!Long.valueOf(CRV_P256).equals(m.get(-1L))) {
            throw new SsoException("webauthn.cose.crv_unsupported");
        }
        byte[] x = bytes(m, -2L);
        byte[] y = bytes(m, -3L);
        if (x.length != 32 || y.length != 32) {
            throw new SsoException("webauthn.cose.invalid");
        }
        ECPoint point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
        validatePoint(point);
        try {
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, P256));
        } catch (GeneralSecurityException e) {
            throw new SsoException("webauthn.cose.invalid");
        }
    }

    static void validatePoint(ECPoint point) {
        EllipticCurve curve = P256.getCurve();
        BigInteger p = ((ECFieldFp) curve.getField()).getP();
        BigInteger x = point.getAffineX();
        BigInteger y = point.getAffineY();
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            throw new SsoException("webauthn.cose.point_invalid");
        }
        BigInteger lhs = y.multiply(y).mod(p);
        BigInteger rhs = x.multiply(x).multiply(x).add(curve.getA().multiply(x)).add(curve.getB()).mod(p);
        if (!lhs.equals(rhs)) {
            throw new SsoException("webauthn.cose.point_invalid");
        }
    }

    private static PublicKey okp(Map<Object, Object> m) {
        allowedLabels(m, Set.of(1L, 2L, 3L, -1L, -2L));
        if (!Long.valueOf(CRV_ED25519).equals(m.get(-1L))) {
            throw new SsoException("webauthn.cose.crv_unsupported");
        }
        byte[] x = bytes(m, -2L);
        if (x.length != 32) {
            throw new SsoException("webauthn.cose.invalid");
        }
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Bytes.concat(ED25519_SPKI_PREFIX, x)));
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new SsoException("webauthn.cose.invalid");
        }
    }

    private static PublicKey rsa(Map<Object, Object> m) {
        allowedLabels(m, Set.of(1L, 2L, 3L, -1L, -2L));
        byte[] nb = bytes(m, -1L);
        byte[] eb = bytes(m, -2L);
        if (nb.length == 0 || nb[0] == 0 || eb.length == 0 || eb.length > 8 || eb[0] == 0) {
            throw new SsoException("webauthn.cose.invalid");
        }
        BigInteger n = new BigInteger(1, nb);
        BigInteger e = new BigInteger(1, eb);
        if (n.bitLength() < MIN_RSA_BITS || n.bitLength() > MAX_RSA_BITS || !n.testBit(0)
                || !e.testBit(0) || e.compareTo(BigInteger.valueOf(3)) < 0) {
            throw new SsoException("webauthn.cose.rsa_invalid");
        }
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
        } catch (GeneralSecurityException ex) {
            throw new SsoException("webauthn.cose.invalid");
        }
    }

    public static CoseKey of(PublicKey key, CoseAlgorithm alg) {
        Map<Object, Object> m = new LinkedHashMap<>();
        if (alg == CoseAlgorithm.ES256 && key instanceof ECPublicKey ec) {
            m.put(1L, (long) KTY_EC2);
            m.put(3L, (long) alg.id());
            m.put(-1L, (long) CRV_P256);
            m.put(-2L, fixed(ec.getW().getAffineX(), 32));
            m.put(-3L, fixed(ec.getW().getAffineY(), 32));
        } else if (alg == CoseAlgorithm.EDDSA && key instanceof EdECPublicKey) {
            byte[] spki = key.getEncoded();
            m.put(1L, (long) KTY_OKP);
            m.put(3L, (long) alg.id());
            m.put(-1L, (long) CRV_ED25519);
            m.put(-2L, Arrays.copyOfRange(spki, spki.length - 32, spki.length));
        } else if ((alg == CoseAlgorithm.RS256 || alg == CoseAlgorithm.PS256) && key instanceof RSAPublicKey rsa) {
            m.put(1L, (long) KTY_RSA);
            m.put(3L, (long) alg.id());
            m.put(-1L, unsigned(rsa.getModulus()));
            m.put(-2L, unsigned(rsa.getPublicExponent()));
        } else {
            throw new SsoException("webauthn.cose.kty_mismatch");
        }
        byte[] enc = Cbor.encode(m);
        return fromMap(m, enc);
    }

    public boolean sameKey(PublicKey other) {
        return other != null && Bytes.constantTimeEquals(publicKey.getEncoded(), other.getEncoded());
    }

    static byte[] fixed(BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        if (raw.length == len) {
            return raw;
        }
        byte[] out = new byte[len];
        if (raw.length > len) {
            System.arraycopy(raw, raw.length - len, out, 0, len);
        } else {
            System.arraycopy(raw, 0, out, len - raw.length, raw.length);
        }
        return out;
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] raw = v.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            return Arrays.copyOfRange(raw, 1, raw.length);
        }
        return raw;
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean isP256(PublicKey key) {
        if (!(key instanceof ECPublicKey ec)) {
            return false;
        }
        ECParameterSpec s = ec.getParams();
        return s.getCurve().equals(P256.getCurve()) && s.getGenerator().equals(P256.getGenerator())
                && s.getOrder().equals(P256.getOrder());
    }

    static boolean keyMatches(CoseAlgorithm alg, PublicKey key) {
        return switch (alg) {
            case ES256 -> isP256(key);
            case EDDSA -> key instanceof EdECPublicKey ed && "Ed25519".equals(ed.getParams().getName());
            case RS256, PS256 -> key instanceof RSAPublicKey r && r.getModulus().bitLength() >= MIN_RSA_BITS;
        };
    }
}
