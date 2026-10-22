package com.naqqa.authenticator.core;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

public final class SecretCipher {

    private static final String PREFIX = "v1.";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final byte[] rawKey;
    private final SecureRandom random;

    public SecretCipher(byte[] key, SecureRandom random) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("encryption key must be exactly 32 bytes");
        }
        this.rawKey = key.clone();
        this.key = new SecretKeySpec(this.rawKey, "AES");
        this.random = random;
    }

    public static SecretCipher fromBase64(String base64Key, SecureRandom random) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("encryption key is not configured");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("encryption key must be base64", e);
        }
        return new SecretCipher(decoded, random);
    }

    public String encrypt(byte[] plaintext, String context) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext);
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return PREFIX + encoder.encodeToString(iv) + "." + encoder.encodeToString(sealed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("secret encryption failed", e);
        }
    }

    public byte[] decrypt(String token, String context) {
        if (token == null || !token.startsWith(PREFIX)) {
            throw new IllegalArgumentException("unsupported secret format");
        }
        String[] parts = token.substring(PREFIX.length()).split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("malformed secret");
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] iv = decoder.decode(parts[0]);
            byte[] sealed = decoder.decode(parts[1]);
            if (iv.length != IV_BYTES) {
                throw new IllegalArgumentException("malformed secret");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(sealed);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("secret cannot be decrypted", e);
        }
    }

    public byte[] deriveKey(String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(rawKey, "HmacSHA256"));
            return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("key derivation failed", e);
        }
    }
}
