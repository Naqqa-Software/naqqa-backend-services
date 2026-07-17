package com.naqqa.outreach.service;

import com.naqqa.outreach.config.OutreachProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encryption for secrets at rest (sender Gmail app passwords). The key is derived as the
 * SHA-256 of {@code naqqa.outreach.secret-key}. Encrypted values carry an {@code enc::} prefix so
 * {@link #decrypt} can tell ciphertext from legacy plaintext — existing plaintext passwords keep
 * working and get re-encrypted on the next write. If no secret is configured the cipher is a no-op
 * pass-through (logged once) so the app never breaks for a missing key.
 */
@Service
@Slf4j
public class SecretCipher {

    private static final String PREFIX = "enc::";
    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(OutreachProperties props) {
        this.key = buildKey(props.getSecretKey());
        if (this.key == null) {
            log.warn("naqqa.outreach.secret-key is not set — sender app passwords are stored as PLAINTEXT.");
        }
    }

    /** Encrypt a secret for storage. Returns the value unchanged if there's no key or it's already encrypted. */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank() || key == null || plain.startsWith(PREFIX)) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            log.error("Secret encryption failed — storing plaintext as a fallback.", e);
            return plain;
        }
    }

    /** Decrypt a stored secret. Returns the value unchanged if it isn't our ciphertext (legacy plaintext). */
    public String decrypt(String value) {
        if (value == null || key == null || !value.startsWith(PREFIX)) {
            return value;
        }
        try {
            byte[] all = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(all, 0, IV_LEN);
            byte[] ct = Arrays.copyOfRange(all, IV_LEN, all.length);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Secret decryption failed for a stored app password.", e);
            return value;
        }
    }

    private static SecretKey buildKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot derive outreach secret key", e);
        }
    }
}
