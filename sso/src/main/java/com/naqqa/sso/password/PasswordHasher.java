package com.naqqa.sso.password;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;

public final class PasswordHasher {
    public static final String ID = "pbkdf2-sha512";
    public static final int VERSION = 1;
    public static final int DEFAULT_ITERATIONS = 210_000;
    public static final int MIN_ITERATIONS = 100_000;
    public static final int MAX_ITERATIONS = 5_000_000;
    public static final int SALT_BYTES = 16;
    public static final int HASH_BYTES = 64;
    public static final int MIN_PEPPER_BYTES = 32;
    public static final int MAX_PASSWORD_CHARS = 1024;

    public record Parsed(int version, int iterations, byte[] salt, byte[] hash) {
        public Parsed {
            salt = salt.clone();
            hash = hash.clone();
        }

        @Override
        public byte[] salt() {
            return salt.clone();
        }

        @Override
        public byte[] hash() {
            return hash.clone();
        }
    }

    private final byte[] pepper;
    private final int iterations;
    private final Parsed dummy;

    public PasswordHasher(byte[] pepper) {
        this(pepper, DEFAULT_ITERATIONS);
    }

    public PasswordHasher(byte[] pepper, int iterations) {
        if (pepper == null || pepper.length < MIN_PEPPER_BYTES) {
            throw new SsoException("password.pepper_too_short");
        }
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new SsoException("password.iterations_invalid");
        }
        this.pepper = pepper.clone();
        this.iterations = iterations;
        byte[] salt = Bytes.random(SALT_BYTES);
        this.dummy = new Parsed(VERSION, iterations, salt, derive(Bytes.randomToken(32), salt, iterations));
    }

    public int iterations() {
        return iterations;
    }

    public String hash(String password) {
        requirePassword(password);
        byte[] salt = Bytes.random(SALT_BYTES);
        byte[] dk = derive(password, salt, iterations);
        try {
            return format(new Parsed(VERSION, iterations, salt, dk));
        } finally {
            Bytes.wipe(dk);
        }
    }

    public boolean verify(String password, String encoded) {
        Parsed p;
        try {
            p = parse(encoded);
        } catch (SsoException e) {
            p = null;
        }
        if (password == null || password.isEmpty() || password.length() > MAX_PASSWORD_CHARS || p == null) {
            verifyAgainst(password == null || password.length() > MAX_PASSWORD_CHARS ? "" : password, dummy);
            return false;
        }
        return verifyAgainst(password, p);
    }

    public boolean verifyUnknownUser(String password) {
        verifyAgainst(password == null || password.length() > MAX_PASSWORD_CHARS ? "" : password, dummy);
        return false;
    }

    public boolean needsRehash(String encoded) {
        try {
            Parsed p = parse(encoded);
            return p.version() != VERSION || p.iterations() < iterations;
        } catch (SsoException e) {
            return true;
        }
    }

    private boolean verifyAgainst(String password, Parsed p) {
        byte[] dk = derive(password, p.salt, p.iterations());
        try {
            return Bytes.constantTimeEquals(dk, p.hash);
        } finally {
            Bytes.wipe(dk);
        }
    }

    private byte[] derive(String password, byte[] salt, int iter) {
        byte[] pre = Hashes.hmacSha256(pepper, Bytes.utf8(PasswordPolicy.normalize(password)));
        char[] chars = Bytes.b64url(pre).toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iter, HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new SsoException("password.hash_failed");
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
            Bytes.wipe(pre);
        }
    }

    private static void requirePassword(String password) {
        if (password == null || password.isEmpty()) {
            throw new SsoException("password.empty");
        }
        if (password.length() > MAX_PASSWORD_CHARS) {
            throw new SsoException("password.too_long");
        }
    }

    public static String format(Parsed p) {
        Base64.Encoder enc = Base64.getEncoder().withoutPadding();
        return "$" + ID + "$v=" + p.version() + "$i=" + p.iterations() + "$" + enc.encodeToString(p.salt) + "$" + enc.encodeToString(p.hash);
    }

    public static Parsed parse(String encoded) {
        if (encoded == null || encoded.length() > 256) {
            throw new SsoException("password.hash_format");
        }
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 6 || !parts[0].isEmpty() || !ID.equals(parts[1])) {
            throw new SsoException("password.hash_format");
        }
        if (!("v=" + VERSION).equals(parts[2]) || !parts[3].startsWith("i=")) {
            throw new SsoException("password.hash_format");
        }
        int iter = parseIterations(parts[3].substring(2));
        byte[] salt = decodeStrict(parts[4]);
        byte[] hash = decodeStrict(parts[5]);
        if (salt.length != SALT_BYTES || hash.length != HASH_BYTES) {
            throw new SsoException("password.hash_format");
        }
        return new Parsed(VERSION, iter, salt, hash);
    }

    private static int parseIterations(String s) {
        if (s.isEmpty() || s.length() > 7 || s.charAt(0) == '0') {
            throw new SsoException("password.hash_format");
        }
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                throw new SsoException("password.hash_format");
            }
            v = v * 10 + (c - '0');
        }
        if (v < MIN_ITERATIONS || v > MAX_ITERATIONS) {
            throw new SsoException("password.hash_format");
        }
        return v;
    }

    private static byte[] decodeStrict(String s) {
        try {
            byte[] b = Base64.getDecoder().decode(s);
            if (!Base64.getEncoder().withoutPadding().encodeToString(b).equals(s)) {
                throw new SsoException("password.hash_format");
            }
            return b;
        } catch (IllegalArgumentException e) {
            throw new SsoException("password.hash_format");
        }
    }
}
