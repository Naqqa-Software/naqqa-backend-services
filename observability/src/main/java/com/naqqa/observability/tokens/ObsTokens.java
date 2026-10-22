package com.naqqa.observability.tokens;

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

public final class ObsTokens {

    public static final String INGEST = "ing";
    public static final String MANAGEMENT = "sk";
    static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";
    static final int ID_LENGTH = 16;
    static final int SECRET_LENGTH = 40;
    static final Pattern FORMAT = Pattern.compile("^obs_(ing|sk)_(live|test)_([a-z2-7]{16})_([a-z2-7]{40})_([0-9a-f]{8})$");

    private final byte[] pepper;
    private final SecureRandom random;

    public ObsTokens(byte[] pepper, SecureRandom random) {
        if (pepper == null || pepper.length < 16) {
            throw new IllegalArgumentException("token pepper must be at least 16 bytes");
        }
        this.pepper = pepper.clone();
        this.random = random;
    }

    public record Parsed(String kind, String env, String id, String secret, String crc) {
    }

    public record Minted(String raw, String kind, String env, String id) {
    }

    public static String crc32(String head) {
        CRC32 crc = new CRC32();
        crc.update(head.getBytes(StandardCharsets.US_ASCII));
        return String.format("%08x", crc.getValue());
    }

    public static String format(String kind, String env, String id, String secret) {
        String head = "obs_" + kind + "_" + env + "_" + id + "_" + secret;
        return head + "_" + crc32(head);
    }

    public static Optional<Parsed> parse(String raw) {
        if (raw == null || raw.length() > 128) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(raw.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        Parsed parsed = new Parsed(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5));
        String expected = crc32("obs_" + parsed.kind() + "_" + parsed.env() + "_" + parsed.id() + "_" + parsed.secret());
        return expected.equals(parsed.crc()) ? Optional.of(parsed) : Optional.empty();
    }

    public static String mask(String raw) {
        Optional<Parsed> parsed = parse(raw);
        return parsed.map(p -> "obs_" + p.kind() + "_" + p.env() + "_" + p.id().substring(0, 4) + "************************")
                .orElse("");
    }

    public Minted mint(String kind, String env) {
        if (!INGEST.equals(kind) && !MANAGEMENT.equals(kind)) {
            throw new IllegalArgumentException("unknown token kind");
        }
        if (!"live".equals(env) && !"test".equals(env)) {
            throw new IllegalArgumentException("unknown token env");
        }
        String id = randomBase32(ID_LENGTH);
        String secret = randomBase32(SECRET_LENGTH);
        return new Minted(format(kind, env, id, secret), kind, env, id);
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
