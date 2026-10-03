package com.naqqa.chatbot.security;

import com.naqqa.chatbot.service.ChatException;
import com.naqqa.chatbot.spi.ChatTokenCodec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class ChatVisitorTokenService {

    public static final String HEADER = "X-Chat-Token";
    public static final String TYPE = "chat_visitor";
    public static final String DEFAULT_ISSUER = "naqqa-chat";

    private final ChatTokenCodec codec;
    private final long ttlHours;
    private final String issuer;
    private final boolean mac;

    public ChatVisitorTokenService(ChatTokenCodec codec, long ttlHours, String issuer) {
        this(codec, ttlHours, issuer, false);
    }

    private ChatVisitorTokenService(ChatTokenCodec codec, long ttlHours, String issuer, boolean mac) {
        this.codec = codec;
        this.ttlHours = ttlHours <= 0 ? 24 : ttlHours;
        this.issuer = issuer == null || issuer.isBlank() ? DEFAULT_ISSUER : issuer.trim();
        this.mac = mac;
    }

    public ChatVisitorTokenService(JwtEncoder encoder, RSAPublicKey publicKey, long ttlHours, String issuer) {
        this(rsa(encoder, publicKey), ttlHours, issuer);
    }

    public static ChatVisitorTokenService hmac(String secret, long ttlHours, String issuer) {
        return new ChatVisitorTokenService(hmacCodec(secret), ttlHours, issuer, true);
    }

    public static ChatTokenCodec rsa(JwtEncoder encoder, RSAPublicKey publicKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        return codec(encoder, decoder);
    }

    public static ChatTokenCodec codec(JwtEncoder encoder, JwtDecoder decoder) {
        return new ChatTokenCodec() {
            @Override
            public JwtEncoder encoder() {
                return encoder;
            }

            @Override
            public JwtDecoder decoder() {
                return decoder;
            }
        };
    }

    public static ChatTokenCodec hmacCodec(String secret) {
        byte[] key;
        try {
            byte[] raw;
            if (secret == null || secret.isBlank()) {
                raw = new byte[32];
                new SecureRandom().nextBytes(raw);
            } else {
                raw = secret.getBytes(StandardCharsets.UTF_8);
            }
            key = MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        SecretKey secretKey = new SecretKeySpec(key, "HmacSHA256");
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
        JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
        return codec(encoder, decoder);
    }

    public String issuer() {
        return issuer;
    }

    public String issue(String conversationId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(ttlHours, ChronoUnit.HOURS))
                .subject(conversationId)
                .claim("typ", TYPE)
                .build();
        JwtEncoderParameters parameters = mac
                ? JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)
                : JwtEncoderParameters.from(claims);
        return codec.encoder().encode(parameters).getTokenValue();
    }

    public String conversationIdOf(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Jwt jwt = codec.decoder().decode(token.trim());
            if (!TYPE.equals(jwt.getClaimAsString("typ")) || !issuer.equals(jwt.getClaimAsString("iss"))) {
                return null;
            }
            if (jwt.getExpiresAt() == null || jwt.getExpiresAt().isBefore(Instant.now().minusSeconds(60))) {
                return null;
            }
            return jwt.getSubject();
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isValidFor(String token, String conversationId) {
        String subject = conversationIdOf(token);
        return subject != null && conversationId != null && subject.equals(conversationId);
    }

    public void verify(String token, String conversationId) {
        if (!isValidFor(token, conversationId)) {
            throw ChatException.forbidden();
        }
    }
}
