package com.naqqa.sso.tokens;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;

public final class ApiTokenHasher {
    public static final String ID = "pbkdf2-sha256";
    public static final int DEFAULT_ITERATIONS = 10_000;
    public static final int MIN_ITERATIONS = 1_000;
    public static final int MAX_ITERATIONS = 1_000_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    private final byte[] pepper;
    private final int iterations;

    public ApiTokenHasher(byte[] pepper, int iterations) {
        if (pepper == null || pepper.length < 32) {
            throw new SsoException("token.pepper_too_short");
        }
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new SsoException("token.iterations_invalid");
        }
        this.pepper = pepper.clone();
        this.iterations = iterations;
    }

    public String hash(String secret) {
        byte[] salt = Bytes.random(SALT_BYTES);
        byte[] dk = derive(secret, salt, iterations);
        Base64.Encoder enc = Base64.getEncoder().withoutPadding();
        return "$" + ID + "$i=" + iterations + "$" + enc.encodeToString(salt) + "$" + enc.encodeToString(dk);
    }

    public boolean verify(String secret, String encoded) {
        String[] parts = encoded == null ? new String[0] : encoded.split("\\$", -1);
        byte[] salt;
        byte[] expected;
        int iter;
        try {
            if (parts.length != 5 || !parts[0].isEmpty() || !ID.equals(parts[1]) || !parts[2].startsWith("i=")) {
                throw new IllegalArgumentException();
            }
            iter = Integer.parseInt(parts[2].substring(2));
            salt = Base64.getDecoder().decode(parts[3]);
            expected = Base64.getDecoder().decode(parts[4]);
            if (iter < MIN_ITERATIONS || iter > MAX_ITERATIONS || salt.length != SALT_BYTES || expected.length != HASH_BYTES) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            derive(secret, new byte[SALT_BYTES], iterations);
            return false;
        }
        byte[] dk = derive(secret, salt, iter);
        try {
            return Bytes.constantTimeEquals(dk, expected);
        } finally {
            Bytes.wipe(dk);
        }
    }

    private byte[] derive(String secret, byte[] salt, int iter) {
        byte[] pre = Hashes.hmacSha256(pepper, Bytes.utf8(secret == null ? "" : secret));
        char[] chars = Bytes.b64url(pre).toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iter, HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new SsoException("token.hash_failed");
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
            Bytes.wipe(pre);
        }
    }
}
