package com.naqqa.analytics.banners.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

public final class BannerTokenService {

    public record Claims(String campaignId, String creativeId, String slot, String lang, String vid, String sid,
                         String bannerId, String pageType, Instant issuedAt) {
    }

    private static final String VERSION = "1";
    private static final int MAX_TOKEN = 1024;
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] key;
    private final Duration maxAge;

    public BannerTokenService(String secret, Duration maxAge) {
        if (secret == null || secret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = random;
        } else {
            this.key = secret.getBytes(StandardCharsets.UTF_8);
        }
        this.maxAge = maxAge == null ? Duration.ofDays(30) : maxAge;
    }

    public String sign(Claims claims) {
        String payload = String.join("|", VERSION, safe(claims.campaignId()), safe(claims.creativeId()), safe(claims.slot()),
                safe(claims.lang()), safe(claims.vid()), safe(claims.sid()), safe(claims.bannerId()), safe(claims.pageType()),
                Long.toString(claims.issuedAt().getEpochSecond()));
        String body = ENC.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + ENC.encodeToString(mac(body));
    }

    public Claims verify(String token, Instant now) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN) {
            return null;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return null;
        }
        String body = token.substring(0, dot);
        byte[] signature;
        String payload;
        try {
            signature = DEC.decode(token.substring(dot + 1));
            payload = new String(DEC.decode(body), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!MessageDigest.isEqual(signature, mac(body))) {
            return null;
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 10 || !VERSION.equals(parts[0])) {
            return null;
        }
        long iat;
        try {
            iat = Long.parseLong(parts[9]);
        } catch (NumberFormatException e) {
            return null;
        }
        Instant issued = Instant.ofEpochSecond(iat);
        if (issued.isAfter(now.plusSeconds(300)) || issued.plus(maxAge).isBefore(now)) {
            return null;
        }
        if (parts[1].isEmpty() || parts[2].isEmpty()) {
            return null;
        }
        return new Claims(parts[1], parts[2], blank(parts[3]), blank(parts[4]), blank(parts[5]), blank(parts[6]),
                blank(parts[7]), blank(parts[8]), issued);
    }

    private byte[] mac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace("|", "").trim();
        return v.length() > 80 ? v.substring(0, 80) : v;
    }

    private static String blank(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
