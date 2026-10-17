package com.naqqa.sso.jose;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.EcCurves;
import com.naqqa.sso.crypto.Jwk;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.util.Bytes;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class Jwe {
    public static final int MAX_TOKEN_SIZE = 64 * 1024;
    public static final String ALG_DIR = "dir";
    public static final String ALG_ECDH_ES = "ECDH-ES";
    public static final String ENC_A256GCM = "A256GCM";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final Set<String> RESERVED = Set.of("alg", "enc", "epk", "apu", "apv", "crit", "zip", "jku", "x5u", "jwk", "x5c");

    private Jwe() {
    }

    public record Decrypted(Map<String, Object> header, byte[] plaintext) {
        public Decrypted {
            header = Collections.unmodifiableMap(new LinkedHashMap<>(header));
            plaintext = plaintext.clone();
        }

        @Override
        public byte[] plaintext() {
            return plaintext.clone();
        }

        public String plaintextUtf8() {
            return new String(plaintext, StandardCharsets.UTF_8);
        }
    }

    public static String encryptDir(byte[] key, byte[] plaintext, Map<String, Object> extraHeader) {
        requireKey(key);
        Map<String, Object> header = baseHeader(ALG_DIR, extraHeader);
        return seal(header, key, plaintext);
    }

    public static Decrypted decryptDir(String token, byte[] key) {
        requireKey(key);
        Parsed p = parse(token);
        if (!ALG_DIR.equals(p.header().get("alg"))) {
            throw new SsoException("jwe.alg_mismatch");
        }
        if (p.header().containsKey("epk")) {
            throw new SsoException("jwe.header_invalid");
        }
        return open(p, key);
    }

    public static String encryptEcdhEs(ECPublicKey recipient, byte[] plaintext, byte[] apu, byte[] apv, Map<String, Object> extraHeader) {
        EcCurves.requireP256(recipient);
        EcCurves.requireOnCurve(recipient.getW());
        KeyPair eph;
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            eph = g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new SsoException("jwe.encrypt_failed");
        }
        Map<String, Object> header = baseHeader(ALG_ECDH_ES, extraHeader);
        header.put("epk", Jwk.ecPublicMembers((ECPublicKey) eph.getPublic()));
        if (apu != null) {
            header.put("apu", Bytes.b64url(apu));
        }
        if (apv != null) {
            header.put("apv", Bytes.b64url(apv));
        }
        byte[] cek = deriveCek((ECPrivateKey) eph.getPrivate(), recipient, apu, apv);
        try {
            return seal(header, cek, plaintext);
        } finally {
            Bytes.wipe(cek);
        }
    }

    public static Decrypted decryptEcdhEs(String token, ECPrivateKey recipient) {
        EcCurves.requireP256(recipient);
        Parsed p = parse(token);
        if (!ALG_ECDH_ES.equals(p.header().get("alg"))) {
            throw new SsoException("jwe.alg_mismatch");
        }
        Object epkValue = p.header().get("epk");
        if (!(epkValue instanceof Map)) {
            throw new SsoException("jwe.header_invalid");
        }
        ECPublicKey epk;
        try {
            epk = Jwk.parseEcPublic(Json.asObject(epkValue));
        } catch (SsoException e) {
            throw new SsoException("jwe.epk_invalid");
        }
        byte[] apu = optionalB64(p.header(), "apu");
        byte[] apv = optionalB64(p.header(), "apv");
        byte[] cek = deriveCek(recipient, epk, apu, apv);
        try {
            return open(p, cek);
        } finally {
            Bytes.wipe(cek);
        }
    }

    static byte[] deriveCek(ECPrivateKey priv, ECPublicKey pub, byte[] apu, byte[] apv) {
        byte[] z;
        try {
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(priv);
            ka.doPhase(pub, true);
            z = ka.generateSecret();
        } catch (GeneralSecurityException | IllegalStateException e) {
            throw new SsoException("jwe.key_agreement_failed");
        }
        try {
            return ConcatKdf.derive(z, KEY_BYTES * 8, ENC_A256GCM, apu, apv);
        } finally {
            Bytes.wipe(z);
        }
    }

    private static Map<String, Object> baseHeader(String alg, Map<String, Object> extra) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", alg);
        header.put("enc", ENC_A256GCM);
        if (extra != null) {
            for (Map.Entry<String, Object> e : extra.entrySet()) {
                if (RESERVED.contains(e.getKey())) {
                    throw new SsoException("jwe.header_forbidden");
                }
                header.put(e.getKey(), e.getValue());
            }
        }
        return header;
    }

    private static String seal(Map<String, Object> header, byte[] cek, byte[] plaintext) {
        String h = Bytes.b64url(Json.writeUtf8(header));
        byte[] iv = Bytes.random(IV_BYTES);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(TAG_BYTES * 8, iv));
            c.updateAAD(h.getBytes(StandardCharsets.US_ASCII));
            byte[] out = c.doFinal(plaintext);
            byte[] ct = Arrays.copyOfRange(out, 0, out.length - TAG_BYTES);
            byte[] tag = Arrays.copyOfRange(out, out.length - TAG_BYTES, out.length);
            String token = h + ".." + Bytes.b64url(iv) + "." + Bytes.b64url(ct) + "." + Bytes.b64url(tag);
            if (token.length() > MAX_TOKEN_SIZE) {
                throw new SsoException("jwe.too_large");
            }
            return token;
        } catch (GeneralSecurityException e) {
            throw new SsoException("jwe.encrypt_failed");
        }
    }

    private static Decrypted open(Parsed p, byte[] cek) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(TAG_BYTES * 8, p.iv()));
            c.updateAAD(p.headerB64().getBytes(StandardCharsets.US_ASCII));
            byte[] pt = c.doFinal(Bytes.concat(p.ciphertext(), p.tag()));
            return new Decrypted(p.header(), pt);
        } catch (GeneralSecurityException e) {
            throw new SsoException("jwe.decrypt_failed");
        }
    }

    record Parsed(String headerB64, Map<String, Object> header, byte[] iv, byte[] ciphertext, byte[] tag) {
    }

    static Parsed parse(String token) {
        if (token == null || token.isEmpty()) {
            throw new SsoException("jwe.malformed");
        }
        if (token.length() > MAX_TOKEN_SIZE) {
            throw new SsoException("jwe.too_large");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 5) {
            throw new SsoException("jwe.malformed");
        }
        if (!parts[1].isEmpty()) {
            throw new SsoException("jwe.encrypted_key_unexpected");
        }
        if (parts[0].isEmpty() || parts[2].isEmpty() || parts[4].isEmpty()) {
            throw new SsoException("jwe.malformed");
        }
        Map<String, Object> header;
        try {
            header = Jws.decodeHeader(parts[0]);
            Jws.checkHeaderPolicy(header);
        } catch (SsoException e) {
            throw new SsoException("jwe.header_invalid");
        }
        if (!ENC_A256GCM.equals(header.get("enc"))) {
            throw new SsoException("jwe.enc_unsupported");
        }
        byte[] iv;
        byte[] ct;
        byte[] tag;
        try {
            iv = Bytes.b64urlDecode(parts[2]);
            ct = Bytes.b64urlDecode(parts[3]);
            tag = Bytes.b64urlDecode(parts[4]);
        } catch (IllegalArgumentException e) {
            throw new SsoException("jwe.bad_base64");
        }
        if (iv.length != IV_BYTES || tag.length != TAG_BYTES) {
            throw new SsoException("jwe.malformed");
        }
        return new Parsed(parts[0], header, iv, ct, tag);
    }

    private static byte[] optionalB64(Map<String, Object> header, String name) {
        Object v = header.get(name);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String)) {
            throw new SsoException("jwe.header_invalid");
        }
        try {
            return Bytes.b64urlDecode((String) v);
        } catch (IllegalArgumentException e) {
            throw new SsoException("jwe.header_invalid");
        }
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new SsoException("jwe.key_length");
        }
    }
}
