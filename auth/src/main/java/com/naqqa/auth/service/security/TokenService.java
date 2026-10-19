package com.naqqa.auth.service.security;

import com.naqqa.auth.exceptions.auth.AccountBlockedException;
import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.entity.authorities.SubRoleEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.security.AccessSessionGuard;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.jose.Jwt;
import com.naqqa.sso.util.Bytes;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class TokenService {

    public static final String ISSUER = "naqqa-auth";
    public static final String TOKEN_USE_CLAIM = "token_use";
    public static final String TOKEN_USE_ACCESS = "access";
    public static final String ENROLLMENT_CLAIM = "mfa_enroll";

    @Value("${jwt.expiration:15}")
    private Integer jwtExpiration;

    private final KeyRing authKeyRing;
    private final Clock clock;
    private final SsoProperties ssoProperties;
    private final ObjectProvider<AccessSessionGuard> sessionGuard;

    public TokenService(KeyRing authKeyRing, Clock clock, SsoProperties ssoProperties) {
        this(authKeyRing, clock, ssoProperties, null);
    }

    @Autowired
    public TokenService(KeyRing authKeyRing, Clock clock, SsoProperties ssoProperties, ObjectProvider<AccessSessionGuard> sessionGuard) {
        this.authKeyRing = authKeyRing;
        this.clock = clock;
        this.ssoProperties = ssoProperties;
        this.sessionGuard = sessionGuard;
    }

    public record AccessContext(String sessionId, Collection<String> amr, String acr, Instant authTime,
                                Map<String, Object> actor, Duration ttl) {

        public AccessContext {
            if (amr == null || amr.isEmpty() || acr == null || acr.isBlank() || authTime == null) {
                throw new IllegalArgumentException("access token context requires amr, acr and auth_time");
            }
            if (actor != null && sessionId == null) {
                throw new IllegalArgumentException("an actor requires a session");
            }
        }
    }

    public static Set<String> authoritiesOf(UserEntity user) {
        Stream<String> roleAuths = user.getRoles()
                .stream()
                .filter(Objects::nonNull)
                .map(RoleEntity::getAuthorities)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .map(AuthorityEntity::getName);
        Stream<String> subRoleAuths = user.getSubRoles()
                .stream()
                .filter(Objects::nonNull)
                .map(SubRoleEntity::getAuthorities)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .map(AuthorityEntity::getName);
        return Stream.concat(roleAuths, subRoleAuths).collect(Collectors.toCollection(TreeSet::new));
    }

    public Duration defaultTtl() {
        return Duration.ofMinutes(jwtExpiration);
    }

    private Map<String, Object> buildClaims(UserEntity user, RoleEntity activeRole, AccessContext ctx) {
        if (activeRole == null) {
            throw new IllegalStateException("Cannot generate token: User has no active role selected.");
        }
        if (ctx == null) {
            throw new IllegalArgumentException("access token context required");
        }
        Instant now = clock.instant();
        if (ctx.sessionId() == null && ssoProperties.isEnabled()
                && ctx.authTime().isAfter(now.minus(ssoProperties.getMfa().getStepUpMaxAge()))) {
            throw new IllegalStateException("a session-less access token cannot carry a fresh authentication");
        }
        Duration ttl = ctx.ttl() != null ? ctx.ttl() : defaultTtl();

        Map<String, Object> roleClaim = new LinkedHashMap<>();
        roleClaim.put("id", activeRole.getId());
        roleClaim.put("name", activeRole.getName());

        List<String> allRoleNames = user.getRoles().stream()
                .filter(Objects::nonNull)
                .map(RoleEntity::getName)
                .collect(Collectors.toCollection(TreeSet::new))
                .stream().toList();

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(ttl).getEpochSecond());
        claims.put("sub", String.valueOf(user.getId()));
        claims.put("jti", Bytes.randomToken(16));
        claims.put(TOKEN_USE_CLAIM, TOKEN_USE_ACCESS);
        claims.put("id", user.getId());
        claims.put("email", user.getEmail());
        claims.put("fullName", user.getFullName());
        claims.put("roles", allRoleNames);
        claims.put("authorities", new ArrayList<>(authoritiesOf(user)));
        claims.put("role", roleClaim);
        if (ctx.sessionId() != null) {
            claims.put("sid", ctx.sessionId());
            AccessSessionGuard guard = sessionGuard == null ? null : sessionGuard.getIfAvailable();
            if (guard != null && guard.enrollmentRequired(ctx.sessionId())) {
                claims.put(ENROLLMENT_CLAIM, true);
            }
        }
        claims.put("amr", new ArrayList<>(new TreeSet<>(ctx.amr())));
        claims.put("acr", ctx.acr());
        claims.put("auth_time", ctx.authTime().getEpochSecond());
        if (ctx.actor() != null) {
            claims.put("act", ctx.actor());
        }
        return claims;
    }

    public String generateAccessToken(UserEntity user, RoleEntity activeRole, AccessContext ctx) {
        if (!user.canSignIn()) {
            // Every access token (sign-in, refresh, role switch, step-up, impersonation) passes here.
            throw new AccountBlockedException();
        }
        return Jwt.sign(authKeyRing, Jwt.TYP_JWT, buildClaims(user, activeRole, ctx));
    }
}
