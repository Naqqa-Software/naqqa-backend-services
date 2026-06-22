package com.naqqa.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256/GCM encryption for chat message content.
 * Each conversation gets its own random key; keys are stored server-side only.
 * Decryption happens server-side — clients never receive the raw key.
 */
@Service
@Slf4j
public class ChatEncryptionService {

    private static final String ALGORITHM     = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LENGTH = 12;   // 96-bit IV
    private static final int    GCM_TAG_BITS  = 128;  // authentication tag

    /** Generates a new random AES-256 key for a conversation (Base64-encoded). */
    public String generateKey() {
        byte[] key = new byte[32]; // 256 bits
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * Encrypts {@code plaintext} with the given Base64 AES-256 key.
     * Returns a Base64 string: [12-byte IV || ciphertext+tag].
     */
    public String encrypt(String plaintext, String base64Key) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV
            byte[] combined = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, GCM_IV_LENGTH, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Chat encryption failed", e);
            throw new RuntimeException("Chat encryption failed", e);
        }
    }

    /**
     * Decrypts a Base64 string produced by {@link #encrypt}.
     * Returns the original plaintext, or {@code null} if ciphertext is null.
     */
    public String decrypt(String ciphertext, String base64Key) {
        if (ciphertext == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            byte[] iv       = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));

            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Chat decryption failed", e);
            return null; // gracefully return null instead of crashing
        }
    }
}
