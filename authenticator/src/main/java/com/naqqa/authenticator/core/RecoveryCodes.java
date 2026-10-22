package com.naqqa.authenticator.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class RecoveryCodes {

    private static final char[] ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final int CODE_LENGTH = 8;

    private final byte[] pepper;
    private final SecureRandom random;

    public RecoveryCodes(byte[] pepper, SecureRandom random) {
        if (pepper == null || pepper.length < 16) {
            throw new IllegalArgumentException("recovery pepper must be at least 16 bytes");
        }
        this.pepper = pepper.clone();
        this.random = random;
    }

    public List<String> generate(int count) {
        List<String> codes = new ArrayList<>(count);
        while (codes.size() < count) {
            StringBuilder raw = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                raw.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            }
            String code = format(raw.toString());
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String raw = input.replace(" ", "").replace("-", "").trim().toLowerCase(Locale.ROOT);
        return raw.length() == CODE_LENGTH ? format(raw) : raw;
    }

    public String hash(String userId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            byte[] digest = mac.doFinal((userId + ":" + normalize(code)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("recovery code hashing failed", e);
        }
    }

    private static String format(String raw) {
        return raw.substring(0, 4) + "-" + raw.substring(4);
    }
}
