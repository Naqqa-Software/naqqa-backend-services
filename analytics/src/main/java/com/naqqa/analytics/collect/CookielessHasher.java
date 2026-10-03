package com.naqqa.analytics.collect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Function;

public final class CookielessHasher {

    public static final String PREFIX = "c-";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Function<String, String> saltForDay;
    private final String pepper;

    public CookielessHasher(Function<String, String> saltForDay) {
        this(saltForDay, "");
    }

    public CookielessHasher(Function<String, String> saltForDay, String pepper) {
        this.saltForDay = saltForDay;
        this.pepper = pepper == null ? "" : pepper;
    }

    public static CookielessHasher withStore(KeyValueStore store, String prefix, String pepper) {
        return new CookielessHasher(day -> store.getOrSet(prefix + "salt:" + day, randomSalt(), Duration.ofHours(50)), pepper);
    }

    public static String randomSalt() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public String visitorId(String day, String userAgent, String truncatedIp, String host) {
        String salt = saltForDay.apply(day);
        String material = pepper + "|" + salt + "|" + day + "|" + nz(userAgent) + "|" + nz(truncatedIp) + "|" + nz(host);
        return PREFIX + sha256(material).substring(0, 32);
    }

    public static boolean isCookieless(String vid) {
        return vid != null && vid.startsWith(PREFIX);
    }

    static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
