package com.naqqa.sso.crypto;

import com.naqqa.sso.SsoException;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

public final class Pem {
    public static final int MAX_PEM_SIZE = 64 * 1024;
    private static final byte[] OID_RSA = Jwk.hexBytes("2a864886f70d010101");
    private static final byte[] OID_EC = Jwk.hexBytes("2a8648ce3d0201");
    private static final byte[] OID_ED25519 = Jwk.hexBytes("2b6570");
    private static final byte[] RSA_ALG_ID = Der.encode(Der.SEQUENCE, Der.encode(Der.OID, OID_RSA), new byte[]{Der.NULL, 0});

    private Pem() {
    }

    public record Block(String label, byte[] der) {
        public Block {
            der = der.clone();
        }

        @Override
        public byte[] der() {
            return der.clone();
        }
    }

    public static Block decode(String pem) {
        if (pem == null || pem.length() > MAX_PEM_SIZE) {
            throw new SsoException("pem.invalid");
        }
        String text = pem.strip().replace("\r\n", "\n");
        String[] lines = text.split("\n", -1);
        if (lines.length < 3) {
            throw new SsoException("pem.invalid");
        }
        String begin = lines[0].strip();
        String end = lines[lines.length - 1].strip();
        if (!begin.startsWith("-----BEGIN ") || !begin.endsWith("-----")) {
            throw new SsoException("pem.invalid");
        }
        String label = begin.substring(11, begin.length() - 5);
        if (label.isEmpty() || !end.equals("-----END " + label + "-----")) {
            throw new SsoException("pem.invalid");
        }
        StringBuilder body = new StringBuilder();
        for (int i = 1; i < lines.length - 1; i++) {
            String line = lines[i].strip();
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
                if (!ok) {
                    throw new SsoException("pem.invalid");
                }
            }
            body.append(line);
        }
        byte[] der;
        try {
            der = Base64.getDecoder().decode(body.toString());
        } catch (IllegalArgumentException e) {
            throw new SsoException("pem.invalid");
        }
        if (der.length == 0 || !Base64.getEncoder().encodeToString(der).equals(body.toString())) {
            throw new SsoException("pem.invalid");
        }
        return new Block(label, der);
    }

    public static String encode(String label, byte[] der) {
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(label).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(b64.length(), i + 64)).append('\n');
        }
        sb.append("-----END ").append(label).append("-----\n");
        return sb.toString();
    }

    public static PublicKey parsePublicKey(String pem) {
        Block block = decode(pem);
        byte[] spki;
        if ("PUBLIC KEY".equals(block.label())) {
            spki = block.der();
        } else if ("RSA PUBLIC KEY".equals(block.label())) {
            spki = Der.encode(Der.SEQUENCE, RSA_ALG_ID, Der.encode(Der.BIT_STRING, new byte[]{0}, block.der()));
        } else {
            throw new SsoException("pem.label_unsupported");
        }
        String alg = keyFactoryFor(spkiOid(spki));
        try {
            return KeyFactory.getInstance(alg).generatePublic(new X509EncodedKeySpec(spki));
        } catch (GeneralSecurityException e) {
            throw new SsoException("pem.key_invalid");
        }
    }

    public static PrivateKey parsePrivateKey(String pem) {
        Block block = decode(pem);
        byte[] pkcs8;
        if ("PRIVATE KEY".equals(block.label())) {
            pkcs8 = block.der();
        } else if ("RSA PRIVATE KEY".equals(block.label())) {
            pkcs8 = Der.encode(Der.SEQUENCE, new byte[]{Der.INTEGER, 1, 0}, RSA_ALG_ID, Der.encode(Der.OCTET_STRING, block.der()));
        } else {
            throw new SsoException("pem.label_unsupported");
        }
        String alg = keyFactoryFor(pkcs8Oid(pkcs8));
        try {
            return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new SsoException("pem.key_invalid");
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }

    public static String encodePublicKey(PublicKey key) {
        return encode("PUBLIC KEY", key.getEncoded());
    }

    public static String encodePrivateKey(PrivateKey key) {
        byte[] der = key.getEncoded();
        try {
            return encode("PRIVATE KEY", der);
        } finally {
            Arrays.fill(der, (byte) 0);
        }
    }

    public static SigningKey signingKey(String privatePem, String publicPem) {
        PrivateKey priv = parsePrivateKey(privatePem);
        PublicKey pub;
        if (publicPem != null) {
            pub = parsePublicKey(publicPem);
        } else if (priv instanceof RSAPrivateCrtKey) {
            RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
            try {
                pub = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            } catch (GeneralSecurityException e) {
                throw new SsoException("pem.key_invalid");
            }
        } else {
            throw new SsoException("pem.public_key_required");
        }
        return SigningKey.of(algFor(priv), pub, priv);
    }

    public static SigningKey verifyingKey(String publicPem) {
        PublicKey pub = parsePublicKey(publicPem);
        return SigningKey.verifyOnly(algFor(pub), pub);
    }

    static SigningAlgorithm algFor(java.security.Key key) {
        if (key instanceof java.security.interfaces.RSAKey) {
            return SigningAlgorithm.RS256;
        }
        if (key instanceof java.security.interfaces.ECKey) {
            return SigningAlgorithm.ES256;
        }
        if (key instanceof java.security.interfaces.EdECKey) {
            return SigningAlgorithm.EdDSA;
        }
        throw new SsoException("crypto.unsupported_alg");
    }

    private static byte[] spkiOid(byte[] spki) {
        Der outer = new Der(spki);
        Der seq = outer.read(Der.SEQUENCE).reader();
        outer.finish();
        Der algId = seq.read(Der.SEQUENCE).reader();
        return algId.read(Der.OID).value();
    }

    private static byte[] pkcs8Oid(byte[] pkcs8) {
        Der outer = new Der(pkcs8);
        Der seq = outer.read(Der.SEQUENCE).reader();
        outer.finish();
        seq.read(Der.INTEGER);
        Der algId = seq.read(Der.SEQUENCE).reader();
        return algId.read(Der.OID).value();
    }

    private static String keyFactoryFor(byte[] oid) {
        if (Arrays.equals(oid, OID_RSA)) {
            return "RSA";
        }
        if (Arrays.equals(oid, OID_EC)) {
            return "EC";
        }
        if (Arrays.equals(oid, OID_ED25519)) {
            return "Ed25519";
        }
        throw new SsoException("pem.algorithm_unsupported");
    }
}
