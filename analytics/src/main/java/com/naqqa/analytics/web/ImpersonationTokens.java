package com.naqqa.analytics.web;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.regex.Pattern;

public class ImpersonationTokens {

    private static final Pattern PART = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final byte[] key;
    private final Clock clock;
    private final long ttlMs;

    public ImpersonationTokens(String secret, Clock clock, long ttlMs) {
        this.key = ("naqqa-analytics-impersonation|" + secret).getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        this.ttlMs = ttlMs;
    }

    public Issued issue(String companyId, String userId) {
        if (companyId == null || !PART.matcher(companyId).matches() || userId == null || !PART.matcher(userId).matches()) {
            throw AnalyticsException.badRequest("Invalid company or user");
        }
        long exp = clock.millis() + ttlMs;
        String payload = companyId + "." + userId + "." + exp;
        return new Issued(payload + "." + sign(payload), companyId, exp);
    }

    public String verify(String token, String userId) {
        if (token == null || userId == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        String payload = parts[0] + "." + parts[1] + "." + parts[2];
        if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), parts[3].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        if (!parts[1].equals(userId)) {
            return null;
        }
        try {
            if (Long.parseLong(parts[2]) < clock.millis()) {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return parts[0];
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record Issued(String token, String companyId, long expiresAt) {
    }
}
