package com.naqqa.auth.service.security;

import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.entity.authorities.SubRoleEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.security.JwtService;
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
    private final JwtService jwtService;

    /**
     * Builds claims for the token.
     * Flattens authorities from the single 'activeRole' and ALL 'SubRoles'.
     */
    private JwtClaimsSet buildClaims(UserEntity user, RoleEntity activeRole, long expirationMinutes) {
        Instant now = Instant.now();

        if (activeRole == null) {
            throw new IllegalStateException("Cannot generate token: User has no active role selected.");
        }

        // 1. Get Authorities from the ACTIVE Role only
        Stream<String> activeRoleAuths = activeRole.getAuthorities()
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
                "id", activeRole.getId(),
                "name", activeRole.getName()
        );

        Set<String> allRoleNames = user.getRoles().stream()
                .map(RoleEntity::getName)
                .collect(Collectors.toSet());

        return JwtClaimsSet.builder()
                .issuer("naqqa-auth")
                .issuedAt(now)
                .expiresAt(now.plus(expirationMinutes, ChronoUnit.MINUTES))
                .subject(String.valueOf(user.getId()))
                .claim("id", user.getId())
                .claim("email", user.getEmail())
                .claim("fullName", user.getFullName())
                .claim("roles", allRoleNames)
                .claim("authorities", flattenedAuthorities) // This is what @PreAuthorize uses
                .claim("role", roleClaim)
                .build();
    }

    public String generateAccessToken(UserEntity user, RoleEntity activeRole) {
        return jwtEncoder.encode(JwtEncoderParameters.from(buildClaims(user, activeRole, jwtExpiration))).getTokenValue();
    }

    public String generateRefreshToken(UserEntity user) {
        return jwtService.generateRefreshToken(String.valueOf(user.getId()));
    }
}
