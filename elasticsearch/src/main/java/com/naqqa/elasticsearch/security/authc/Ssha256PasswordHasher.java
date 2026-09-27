package com.naqqa.elasticsearch.security.authc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class Ssha256PasswordHasher implements PasswordHasher {

    public static final String PREFIX = "{SSHA256}";
    private static final int SALT_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String hash(char[] password) {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        byte[] digest = digest(password, salt);
        byte[] combined = new byte[salt.length + digest.length];
        System.arraycopy(salt, 0, combined, 0, salt.length);
        System.arraycopy(digest, 0, combined, salt.length, digest.length);
        return PREFIX + Base64.getEncoder().encodeToString(combined);
    }

    @Override
    public boolean verify(char[] password, String hash) {
        if (!canHandle(hash)) {
            return false;
        }
        byte[] combined = Base64.getDecoder().decode(hash.substring(PREFIX.length()));
        if (combined.length <= SALT_LENGTH) {
            return false;
        }
        byte[] salt = Arrays.copyOfRange(combined, 0, SALT_LENGTH);
        byte[] expected = Arrays.copyOfRange(combined, SALT_LENGTH, combined.length);
        byte[] actual = digest(password, salt);
        return MessageDigest.isEqual(expected, actual);
    }

    @Override
    public boolean canHandle(String hash) {
        return hash != null && hash.startsWith(PREFIX);
    }

    private static byte[] digest(char[] password, byte[] salt) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] passwordBytes = new String(password).getBytes(StandardCharsets.UTF_8);
            sha256.update(salt);
            sha256.update(passwordBytes);
            return sha256.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
