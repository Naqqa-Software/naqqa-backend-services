package com.naqqa.sso.crypto;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECKey;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;

public final class SigningKey {
    public static final int RSA_MIN_BITS = 2048;
    public static final int RSA_MAX_BITS = 8192;
    private static final byte[] PAIRING_PROBE = Bytes.utf8("naqqa-sso-key-pairing-probe");

    private final SigningAlgorithm alg;
    private final PublicKey publicKey;
    private final PrivateKey privateKey;
    private final String kid;

    private SigningKey(SigningAlgorithm alg, PublicKey publicKey, PrivateKey privateKey, String kid) {
        this.alg = alg;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.kid = kid;
    }

    public static SigningKey of(SigningAlgorithm alg, PublicKey publicKey, PrivateKey privateKey) {
        if (alg == null || publicKey == null) {
            throw new SsoException("crypto.key_invalid");
        }
        validatePublic(alg, publicKey);
        if (privateKey != null) {
            validatePrivate(alg, privateKey);
        }
        SigningKey key = new SigningKey(alg, publicKey, privateKey, Jwk.thumbprint(publicKey));
        if (privateKey != null && !key.verify(PAIRING_PROBE, key.sign(PAIRING_PROBE))) {
            throw new SsoException("crypto.key_pair_mismatch");
        }
        return key;
    }

    public static SigningKey verifyOnly(SigningAlgorithm alg, PublicKey publicKey) {
        return of(alg, publicKey, null);
    }

    public static SigningKey generate(SigningAlgorithm alg) {
        try {
            KeyPairGenerator g;
            switch (alg) {
                case RS256:
                    g = KeyPairGenerator.getInstance("RSA");
                    g.initialize(new RSAKeyGenParameterSpec(RSA_MIN_BITS, RSAKeyGenParameterSpec.F4));
                    break;
                case ES256:
                    g = KeyPairGenerator.getInstance("EC");
                    g.initialize(new ECGenParameterSpec("secp256r1"));
                    break;
                default:
                    g = KeyPairGenerator.getInstance("Ed25519");
            }
            KeyPair kp = g.generateKeyPair();
            return of(alg, kp.getPublic(), kp.getPrivate());
        } catch (GeneralSecurityException e) {
            throw new SsoException("crypto.keygen_failed", e);
        }
    }

    public static SigningKey generateRsa(int bits) {
        if (bits < RSA_MIN_BITS || bits > RSA_MAX_BITS) {
            throw new SsoException("crypto.rsa_size");
        }
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(new RSAKeyGenParameterSpec(bits, RSAKeyGenParameterSpec.F4));
            KeyPair kp = g.generateKeyPair();
            return of(SigningAlgorithm.RS256, kp.getPublic(), kp.getPrivate());
        } catch (GeneralSecurityException e) {
            throw new SsoException("crypto.keygen_failed", e);
        }
    }

    static void validatePublic(SigningAlgorithm alg, PublicKey key) {
        switch (alg) {
            case RS256:
                if (!(key instanceof RSAPublicKey)) {
                    throw new SsoException("crypto.key_type_mismatch");
                }
                RSAPublicKey rsa = (RSAPublicKey) key;
                int bits = rsa.getModulus().bitLength();
                if (bits < RSA_MIN_BITS || bits > RSA_MAX_BITS) {
                    throw new SsoException("crypto.rsa_size");
                }
                BigInteger e = rsa.getPublicExponent();
                if (!e.testBit(0) || e.compareTo(BigInteger.valueOf(3)) < 0 || e.bitLength() > 32) {
                    throw new SsoException("crypto.rsa_exponent");
                }
                break;
            case ES256:
                if (!(key instanceof ECPublicKey)) {
                    throw new SsoException("crypto.key_type_mismatch");
                }
                EcCurves.requireP256((ECPublicKey) key);
                EcCurves.requireOnCurve(((ECPublicKey) key).getW());
                break;
            default:
                if (!(key instanceof EdECPublicKey)) {
                    throw new SsoException("crypto.key_type_mismatch");
                }
                requireEd25519((EdECKey) key);
        }
    }

    static void validatePrivate(SigningAlgorithm alg, PrivateKey key) {
        switch (alg) {
            case RS256:
                if (!(key instanceof RSAPrivateKey)) {
                    throw new SsoException("crypto.key_type_mismatch");
                }
                int bits = ((RSAPrivateKey) key).getModulus().bitLength();
                if (bits < RSA_MIN_BITS || bits > RSA_MAX_BITS) {
                    throw new SsoException("crypto.rsa_size");
                }
                break;
            case ES256:
                if (!(key instanceof ECPrivateKey)) {
                    throw new SsoException("crypto.key_type_mismatch");
                }
                EcCurves.requireP256((ECPrivateKey) key);
                break;
            default:
                if (!(key instanceof EdECPrivateKey)) {
                    throw new SsoException("crypto.key_type_mismatch");
                }
                requireEd25519((EdECKey) key);
        }
    }

    private static void requireEd25519(EdECKey key) {
        if (!"Ed25519".equalsIgnoreCase(key.getParams().getName())) {
            throw new SsoException("crypto.curve_unsupported");
        }
    }

    public SigningAlgorithm alg() {
        return alg;
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    public PrivateKey privateKey() {
        return privateKey;
    }

    public String kid() {
        return kid;
    }

    public String thumbprint() {
        return Jwk.thumbprint(publicKey);
    }

    public boolean canSign() {
        return privateKey != null;
    }

    public SigningKey publicOnly() {
        return new SigningKey(alg, publicKey, null, kid);
    }

    public SigningKey withKid(String newKid) {
        return new SigningKey(alg, publicKey, privateKey, requireValidKid(newKid));
    }

    public static String requireValidKid(String kid) {
        if (!isValidKid(kid)) {
            throw new SsoException("crypto.kid_invalid");
        }
        return kid;
    }

    public static boolean isValidKid(String kid) {
        if (kid == null || kid.isEmpty() || kid.length() > 128) {
            return false;
        }
        for (int i = 0; i < kid.length(); i++) {
            char c = kid.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    public byte[] sign(byte[] data) {
        if (privateKey == null) {
            throw new SsoException("crypto.verify_only_key");
        }
        try {
            Signature s = Signature.getInstance(alg.jca());
            s.initSign(privateKey);
            s.update(data);
            byte[] sig = s.sign();
            return alg == SigningAlgorithm.ES256 ? EcdsaSignatures.derToRaw(sig) : sig;
        } catch (GeneralSecurityException e) {
            throw new SsoException("crypto.sign_failed", e);
        }
    }

    public boolean verify(byte[] data, byte[] signature) {
        if (data == null || signature == null) {
            return false;
        }
        try {
            byte[] sig;
            switch (alg) {
                case RS256:
                    int len = (((RSAPublicKey) publicKey).getModulus().bitLength() + 7) / 8;
                    if (signature.length != len) {
                        return false;
                    }
                    sig = signature;
                    break;
                case ES256:
                    if (signature.length != 2 * EcdsaSignatures.P256_COMPONENT) {
                        return false;
                    }
                    sig = EcdsaSignatures.rawToDer(signature);
                    break;
                default:
                    if (signature.length != 64) {
                        return false;
                    }
                    sig = signature;
            }
            Signature s = Signature.getInstance(alg.jca());
            s.initVerify(publicKey);
            s.update(data);
            return s.verify(sig);
        } catch (GeneralSecurityException | SsoException | IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "SigningKey[" + alg.jwa() + "," + kid + "," + (privateKey != null ? "private" : "public") + "]";
    }
}
