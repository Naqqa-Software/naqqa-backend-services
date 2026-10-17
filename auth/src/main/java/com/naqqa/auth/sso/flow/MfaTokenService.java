package com.naqqa.auth.sso.flow;

import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.jose.Jwt;
import com.naqqa.sso.jose.JwtClaims;
import com.naqqa.sso.jose.JwtValidator;
import com.naqqa.sso.util.Bytes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class MfaTokenService {

    public static final String TYP = "mfa+jwt";
    public static final String AUDIENCE = "naqqa-auth:mfa";
    public static final String USED_KIND = "mfa-jti";
    public static final String EPOCH_KIND = "mfa-epoch";

    public record Context(String userId, List<String> amr, List<String> methods, String clientId, String deviceId,
                          String clientType, boolean rememberMe, boolean rememberDevice, String jti, Instant expiresAt) {
    }

    private final KeyRing ring;
    private final Clock clock;
    private final Duration ttl;
    private final JwtValidator validator;
    private final SsoTicketStore tickets;

    public MfaTokenService(KeyRing authKeyRing, Clock clock, SsoProperties properties, SsoTicketStore tickets) {
        this.ring = authKeyRing;
        this.clock = clock;
        this.ttl = properties.getMfa().getMfaTokenTtl();
        this.tickets = tickets;
        this.validator = JwtValidator.builder(clock)
                .issuer(TokenService.ISSUER)
                .audience(AUDIENCE)
                .type(TYP)
                .requireIat(true)
                .requireClaim("jti")
                .requireClaim("sub")
                .maxLifetime(ttl)
                .leeway(Duration.ofSeconds(5))
                .build();
    }

    public String issue(String userId, Collection<String> amr, Collection<String> methods, String clientId, String deviceId,
                        String clientType, boolean rememberMe) {
        Instant now = clock.instant();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", TokenService.ISSUER);
        claims.put("aud", AUDIENCE);
        claims.put("sub", userId);
        claims.put("jti", Bytes.randomToken(18));
        claims.put("iat", now.getEpochSecond());
        claims.put("iat_ms", now.toEpochMilli());
        claims.put("exp", now.plus(ttl).getEpochSecond());
        claims.put("amr", new ArrayList<>(new TreeSet<>(amr)));
        claims.put("methods", new ArrayList<>(methods));
        if (clientId != null) {
            claims.put("cid", clientId);
        }
        if (deviceId != null) {
            claims.put("did", deviceId);
        }
        if (clientType != null) {
            claims.put("ctp", clientType);
        }
        claims.put("rme", rememberMe);
        return Jwt.sign(ring, TYP, claims);
    }

    public Context parse(String token) {
        Jwt.Verified v;
        try {
            v = validator.verify(token, ring);
        } catch (SsoException e) {
            throw SsoApiException.unauthorized("MFA_TOKEN_INVALID", "MFA session is invalid or expired");
        } catch (RuntimeException e) {
            throw SsoApiException.unauthorized("MFA_TOKEN_INVALID", "MFA session is invalid or expired");
        }
        JwtClaims c = v.claims();
        if (tickets.peek(USED_KIND, c.jwtId()).isPresent() || issuedBeforeEpoch(c)) {
            throw SsoApiException.unauthorized("MFA_TOKEN_INVALID", "MFA session is invalid or expired");
        }
        return new Context(c.subject(), strings(c.get("amr")), strings(c.get("methods")), c.string("cid"), c.string("did"),
                c.string("ctp"), Boolean.TRUE.equals(c.get("rme")), false, c.jwtId(), c.expiresAt());
    }

    private boolean issuedBeforeEpoch(JwtClaims c) {
        Instant iat = c.issuedAt();
        return tickets.peek(EPOCH_KIND, c.subject())
                .map(t -> iat == null || issuedAtMillis(c, iat) <= Long.parseLong(t.data().getOrDefault("atMs", "0")))
                .orElse(false);
    }

    private static long issuedAtMillis(JwtClaims c, Instant iat) {
        Object ms = c.get("iat_ms");
        if (ms instanceof Number n && n.longValue() / 1000 == iat.getEpochSecond()) {
            return n.longValue();
        }
        return iat.getEpochSecond() * 1000L + 999L;
    }

    public void revokeIssuedBefore(String userId) {
        tickets.put(EPOCH_KIND, userId, userId, Map.of("atMs", Long.toString(clock.instant().toEpochMilli())),
                ttl.plus(Duration.ofMinutes(5)));
    }

    public void consume(Context context) {
        Instant keep = context.expiresAt().plus(Duration.ofMinutes(5));
        if (!tickets.markUsed(USED_KIND, context.jti(), keep)) {
            throw SsoApiException.unauthorized("MFA_TOKEN_INVALID", "MFA session is invalid or expired");
        }
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof String s) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
