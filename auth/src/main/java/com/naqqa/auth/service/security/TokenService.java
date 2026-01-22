package com.naqqa.auth.service.security;

import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.entity.authorities.SubRoleEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RequiredArgsConstructor
@Service
public class TokenService {

    @Value("${jwt.expiration:15}")
    private Integer jwtExpiration;

    @Value("${jwt.expiration-remember:1440}")
    private Integer jwtExpirationRemember;

    private final JwtEncoder jwtEncoder;

    /**
     * Builds claims for the token.
     * Flattens authorities from the single 'LastRole' and ALL 'SubRoles'.
     */
    private JwtClaimsSet buildClaims(UserEntity user, long expirationMinutes) {
        Instant now = Instant.now();

        if (user.getLastRole() == null) {
            throw new IllegalStateException("Cannot generate token: User has no active role selected.");
        }

        // 1. Get Authorities from the ACTIVE Role only
        Stream<String> activeRoleAuths = user.getLastRole().getAuthorities()
                .stream()
                .map(AuthorityEntity::getName);

        // 2. Get Authorities from ALL SubRoles assigned to the user
        // Note: These stay with the user regardless of which main Role they switch to
        Stream<String> subRoleAuths = user.getSubRoles()
                .stream()
                .map(SubRoleEntity::getAuthorities)
                .flatMap(Collection::stream)
                .map(AuthorityEntity::getName);

        // 3. Flatten into a single distinct Set
        Set<String> flattenedAuthorities = Stream.concat(activeRoleAuths, subRoleAuths)
                .collect(Collectors.toSet());

        // Construct a simple role claim for the UI
        Map<String, Object> roleClaim = Map.of(
                "id", user.getLastRole().getId(),
                "name", user.getLastRole().getName()
        );

        return JwtClaimsSet.builder()
                .issuer("naqqa-auth")
                .issuedAt(now)
                .expiresAt(now.plus(expirationMinutes, ChronoUnit.MINUTES))
                .subject(String.valueOf(user.getId()))
                .claim("authorities", flattenedAuthorities) // This is what @PreAuthorize uses
                .claim("role", roleClaim)
                .build();
    }

    public String generateToken(UserEntity user) {
        return generateShortLivedToken(user);
    }

    public String generateShortLivedToken(UserEntity user) {
        return jwtEncoder.encode(JwtEncoderParameters.from(buildClaims(user, jwtExpiration))).getTokenValue();
    }

    public String generateLongLivedToken(UserEntity user) {
        return jwtEncoder.encode(JwtEncoderParameters.from(buildClaims(user, jwtExpirationRemember))).getTokenValue();
    }
}