package com.naqqa.elasticsearch.security.authc;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Pbkdf2PasswordHasher implements PasswordHasher {

    public static final String PREFIX = "{PBKDF2}";
    private static final int KEY_LENGTH_BITS = 512;
    private static final int DEFAULT_ITERATIONS = 10000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int iterations;

    public Pbkdf2PasswordHasher() {
        this(DEFAULT_ITERATIONS);
    }

    public Pbkdf2PasswordHasher(int iterations) {
        this.iterations = iterations;
    }

    @Override
    public String hash(char[] password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] derived = pbkdf2(password, salt, iterations);
        return PREFIX + iterations + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derived);
    }

    @Override
    public boolean verify(char[] password, String hash) {
        if (!canHandle(hash)) {
            return false;
        }
        String body = hash.substring(PREFIX.length());
        String[] parts = body.split("\\$");
        if (parts.length != 3) {
            return false;
        }
        int storedIterations = Integer.parseInt(parts[0]);
        byte[] salt = Base64.getDecoder().decode(parts[1]);
        byte[] expected = Base64.getDecoder().decode(parts[2]);
        byte[] actual = pbkdf2(password, salt, storedIterations);
        return MessageDigest.isEqual(expected, actual);
    }

    @Override
    public boolean canHandle(String hash) {
        return hash != null && hash.startsWith(PREFIX);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
            return factory.generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to compute PBKDF2 hash", e);
        }
    }
}
