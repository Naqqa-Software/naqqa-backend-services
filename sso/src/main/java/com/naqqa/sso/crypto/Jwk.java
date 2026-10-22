package com.naqqa.sso.crypto;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Jwk {
    private static final byte[] ED25519_SPKI_PREFIX = hexBytes("302a300506032b6570032100");
    private static final byte[] ED25519_PKCS8_PREFIX = hexBytes("302e020100300506032b657004220420");
    private static final Set<String> PRIVATE_MEMBERS = Set.of("d", "p", "q", "dp", "dq", "qi", "oth", "k");

    private Jwk() {
    }

    public static String thumbprint(PublicKey key) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (key instanceof RSAPublicKey) {
            RSAPublicKey rsa = (RSAPublicKey) key;
            m.put("e", b64(unsigned(rsa.getPublicExponent())));
            m.put("kty", "RSA");
            m.put("n", b64(unsigned(rsa.getModulus())));
        } else if (key instanceof ECPublicKey) {
            ECPublicKey ec = (ECPublicKey) key;
            EcCurves.requireP256(ec);
            m.put("crv", "P-256");
            m.put("kty", "EC");
            m.put("x", b64(fixed(ec.getW().getAffineX(), 32)));
            m.put("y", b64(fixed(ec.getW().getAffineY(), 32)));
        } else if (key instanceof EdECPublicKey) {
            m.put("crv", "Ed25519");
            m.put("kty", "OKP");
            m.put("x", b64(ed25519Raw((EdECPublicKey) key)));
        } else {
            throw new SsoException("jwk.unsupported_kty");
        }
        return Bytes.b64url(Hashes.sha256(Bytes.utf8(Json.writeCanonical(m))));
    }

    public static Map<String, Object> toPublicJwk(SigningKey key) {
        Map<String, Object> m = new LinkedHashMap<>();
        PublicKey pub = key.publicKey();
        m.put("kty", key.alg().kty());
        if (pub instanceof RSAPublicKey) {
            RSAPublicKey rsa = (RSAPublicKey) pub;
            m.put("n", b64(unsigned(rsa.getModulus())));
            m.put("e", b64(unsigned(rsa.getPublicExponent())));
        } else if (pub instanceof ECPublicKey) {
            m.putAll(ecPublicMembers((ECPublicKey) pub));
        } else {
            m.put("crv", "Ed25519");
            m.put("x", b64(ed25519Raw((EdECPublicKey) pub)));
        }
        m.put("kid", key.kid());
        m.put("alg", key.alg().jwa());
        m.put("use", "sig");
        return m;
    }

    public static Map<String, Object> toPrivateJwk(SigningKey key) {
        if (!key.canSign()) {
            throw new SsoException("crypto.verify_only_key");
        }
        Map<String, Object> m = toPublicJwk(key);
        PrivateKey priv = key.privateKey();
        if (priv instanceof RSAPrivateCrtKey) {
            RSAPrivateCrtKey k = (RSAPrivateCrtKey) priv;
            m.put("d", b64(unsigned(k.getPrivateExponent())));
            m.put("p", b64(unsigned(k.getPrimeP())));
            m.put("q", b64(unsigned(k.getPrimeQ())));
            m.put("dp", b64(unsigned(k.getPrimeExponentP())));
            m.put("dq", b64(unsigned(k.getPrimeExponentQ())));
            m.put("qi", b64(unsigned(k.getCrtCoefficient())));
        } else if (priv instanceof RSAPrivateKey) {
            m.put("d", b64(unsigned(((RSAPrivateKey) priv).getPrivateExponent())));
        } else if (priv instanceof ECPrivateKey) {
            m.put("d", b64(fixed(((ECPrivateKey) priv).getS(), 32)));
        } else if (priv instanceof EdECPrivateKey) {
            byte[] d = ((EdECPrivateKey) priv).getBytes().orElseThrow(() -> new SsoException("jwk.private_unavailable"));
            m.put("d", b64(d));
        }
        return m;
    }

    public static Map<String, Object> ecPublicMembers(ECPublicKey pub) {
        EcCurves.requireP256(pub);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kty", "EC");
        m.put("crv", "P-256");
        m.put("x", b64(fixed(pub.getW().getAffineX(), 32)));
        m.put("y", b64(fixed(pub.getW().getAffineY(), 32)));
        return m;
    }

    public static boolean hasPrivateMembers(Map<String, Object> jwk) {
        for (String k : PRIVATE_MEMBERS) {
            if (jwk.containsKey(k)) {
                return true;
            }
        }
        return false;
    }

    public static SigningKey parse(String json) {
        return parse(Json.parseObject(json));
    }

    public static SigningKey parse(Map<String, Object> jwk) {
        String kty = Json.requireString(jwk, "kty");
        String use = Json.optString(jwk, "use");
        if (use != null && !"sig".equals(use)) {
            throw new SsoException("jwk.use_mismatch");
        }
        List<String> ops = Json.optStringList(jwk, "key_ops");
        if (ops != null && !ops.contains("verify") && !ops.contains("sign")) {
            throw new SsoException("jwk.use_mismatch");
        }
        String algName = Json.optString(jwk, "alg");
        SigningKey key;
        switch (kty) {
            case "RSA":
                key = parseRsa(jwk);
                break;
            case "EC":
                key = parseEc(jwk);
                break;
            case "OKP":
                key = parseOkp(jwk);
                break;
            default:
                throw new SsoException("jwk.unsupported_kty");
        }
        if (algName != null && !algName.equals(key.alg().jwa())) {
            throw new SsoException("jwk.alg_mismatch");
        }
        String kid = Json.optString(jwk, "kid");
        return kid == null ? key : key.withKid(kid);
    }

    private static SigningKey parseRsa(Map<String, Object> jwk) {
        BigInteger n = positive(member(jwk, "n"), true);
        BigInteger e = positive(member(jwk, "e"), true);
        if (jwk.containsKey("oth")) {
            throw new SsoException("jwk.rsa_multiprime");
        }
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey pub = kf.generatePublic(new RSAPublicKeySpec(n, e));
            PrivateKey priv = null;
            if (jwk.containsKey("d")) {
                BigInteger d = positive(member(jwk, "d"), false);
                String[] crt = {"p", "q", "dp", "dq", "qi"};
                int present = 0;
                for (String c : crt) {
                    if (jwk.containsKey(c)) {
                        present++;
                    }
                }
                if (present == crt.length) {
                    priv = kf.generatePrivate(new RSAPrivateCrtKeySpec(n, e, d,
                            positive(member(jwk, "p"), false), positive(member(jwk, "q"), false),
                            positive(member(jwk, "dp"), false), positive(member(jwk, "dq"), false),
                            positive(member(jwk, "qi"), false)));
                } else if (present == 0) {
                    priv = kf.generatePrivate(new RSAPrivateKeySpec(n, d));
                } else {
                    throw new SsoException("jwk.rsa_crt_incomplete");
                }
            } else if (hasPrivateMembers(jwk)) {
                throw new SsoException("jwk.rsa_crt_incomplete");
            }
            return SigningKey.of(SigningAlgorithm.RS256, pub, priv);
        } catch (GeneralSecurityException ex) {
            throw new SsoException("jwk.invalid");
        }
    }

    private static SigningKey parseEc(Map<String, Object> jwk) {
        if (!"P-256".equals(Json.requireString(jwk, "crv"))) {
            throw new SsoException("crypto.curve_unsupported");
        }
        ECPublicKey pub = ecPublicKey(member(jwk, "x"), member(jwk, "y"));
        PrivateKey priv = null;
        if (jwk.containsKey("d")) {
            byte[] d = member(jwk, "d");
            if (d.length != 32) {
                throw new SsoException("jwk.ec_length");
            }
            BigInteger s = new BigInteger(1, d);
            if (s.signum() == 0 || s.compareTo(EcCurves.P256.getOrder()) >= 0) {
                throw new SsoException("jwk.ec_private_range");
            }
            try {
                priv = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(s, EcCurves.P256));
            } catch (GeneralSecurityException ex) {
                throw new SsoException("jwk.invalid");
            }
        }
        return SigningKey.of(SigningAlgorithm.ES256, pub, priv);
    }

    public static ECPublicKey ecPublicKey(byte[] x, byte[] y) {
        if (x.length != 32 || y.length != 32) {
            throw new SsoException("jwk.ec_length");
        }
        ECPoint w = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
        EcCurves.requireOnCurve(w);
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, EcCurves.P256));
        } catch (GeneralSecurityException ex) {
            throw new SsoException("crypto.point_invalid");
        }
    }

    public static ECPublicKey parseEcPublic(Map<String, Object> jwk) {
        if (!"EC".equals(Json.requireString(jwk, "kty")) || !"P-256".equals(Json.requireString(jwk, "crv"))) {
            throw new SsoException("crypto.curve_unsupported");
        }
        if (hasPrivateMembers(jwk)) {
            throw new SsoException("jwk.private_material");
        }
        return ecPublicKey(member(jwk, "x"), member(jwk, "y"));
    }

    private static SigningKey parseOkp(Map<String, Object> jwk) {
        if (!"Ed25519".equals(Json.requireString(jwk, "crv"))) {
            throw new SsoException("crypto.curve_unsupported");
        }
        byte[] x = member(jwk, "x");
        if (x.length != 32) {
            throw new SsoException("jwk.okp_length");
        }
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(Bytes.concat(ED25519_SPKI_PREFIX, x)));
            PrivateKey priv = null;
            if (jwk.containsKey("d")) {
                byte[] d = member(jwk, "d");
                if (d.length != 32) {
                    throw new SsoException("jwk.okp_length");
                }
                priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Bytes.concat(ED25519_PKCS8_PREFIX, d)));
            }
            return SigningKey.of(SigningAlgorithm.EdDSA, pub, priv);
        } catch (GeneralSecurityException ex) {
            throw new SsoException("jwk.invalid");
        }
    }

    static byte[] ed25519Raw(EdECPublicKey key) {
        byte[] enc = key.getEncoded();
        if (enc.length != 44 || !Arrays.equals(Arrays.copyOf(enc, 12), ED25519_SPKI_PREFIX)) {
            throw new SsoException("crypto.key_invalid");
        }
        return Arrays.copyOfRange(enc, 12, 44);
    }

    private static byte[] member(Map<String, Object> jwk, String name) {
        String v = Json.requireString(jwk, name);
        try {
            return Bytes.b64urlDecode(v);
        } catch (IllegalArgumentException e) {
            throw new SsoException("jwk.bad_base64");
        }
    }

    private static BigInteger positive(byte[] v, boolean minimal) {
        if (v.length == 0 || (minimal && v[0] == 0)) {
            throw new SsoException("jwk.bad_integer");
        }
        BigInteger b = new BigInteger(1, v);
        if (b.signum() <= 0) {
            throw new SsoException("jwk.bad_integer");
        }
        return b;
    }

    static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            return Arrays.copyOfRange(b, 1, b.length);
        }
        return b;
    }

    static byte[] fixed(BigInteger v, int len) {
        byte[] b = unsigned(v);
        if (b.length > len) {
            throw new SsoException("crypto.key_invalid");
        }
        byte[] out = new byte[len];
        System.arraycopy(b, 0, out, len - b.length, b.length);
        return out;
    }

    private static String b64(byte[] b) {
        return Bytes.b64url(b);
    }

    static byte[] hexBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
