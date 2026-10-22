package com.naqqa.registry.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

public final class RegistryTokens {

    public static final String PREFIX = "nqr_";
    static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";
    static final int ID_LENGTH = 16;
    static final int SECRET_LENGTH = 40;
    static final int MAX_LENGTH = 128;
    static final Pattern FORMAT = Pattern.compile("^nqr_([a-z2-7]{16})_([a-z2-7]{40})_([0-9a-f]{8})$");

    private final byte[] pepper;
    private final SecureRandom random;

    public RegistryTokens(byte[] pepper, SecureRandom random) {
        if (pepper == null || pepper.length < 16) {
            throw new IllegalArgumentException("token pepper must be at least 16 bytes");
        }
        this.pepper = pepper.clone();
        this.random = random;
    }

    public record Parsed(String id, String secret) {
    }

    public record Minted(String raw, String id) {
    }

    public static Optional<Parsed> parse(String raw) {
        if (raw == null || raw.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(raw.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        String head = PREFIX + m.group(1) + "_" + m.group(2);
        return crc32(head).equals(m.group(3)) ? Optional.of(new Parsed(m.group(1), m.group(2))) : Optional.empty();
    }

    public static String mask(String id) {
        return PREFIX + id + "_****";
    }

    static String crc32(String head) {
        CRC32 crc = new CRC32();
        crc.update(head.getBytes(StandardCharsets.US_ASCII));
        return String.format("%08x", crc.getValue());
    }

    public Minted mint() {
        String id = randomBase32(ID_LENGTH);
        String head = PREFIX + id + "_" + randomBase32(SECRET_LENGTH);
        return new Minted(head + "_" + crc32(head), id);
    }

    public String hash(String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(raw.trim().getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("token hashing failed", e);
        }
    }

    public boolean matches(String raw, String storedHash) {
        if (raw == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(raw).getBytes(StandardCharsets.US_ASCII), storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    private String randomBase32(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        StringBuilder out = new StringBuilder(length);
        for (byte b : bytes) {
            out.append(ALPHABET.charAt(b & 31));
        }
        return out.toString();
    }
}
