package com.naqqa.auth.service;

import com.naqqa.auth.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@RequiredArgsConstructor
@Service
public class TokenService {

    @Value("${jwt.expiration}")
    private Integer jwtExpiration;

    @Value("${jwt.expiration-remember}")
    private Integer jwtExpirationRemember;

    private final JwtEncoder jwtEncoder;

    public String generateToken(UserEntity user) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("naqqa-auth")
                .issuedAt(now)
                .expiresAt(now.plus(jwtExpiration, ChronoUnit.MINUTES))
                .subject(String.valueOf(user.getId()))
                .claim("authorities", user.getRole())
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }


    public String generateShortLivedToken(UserEntity user) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("naqqa-auth")
                .issuedAt(now)
                .expiresAt(now.plus(jwtExpiration, ChronoUnit.MINUTES))
                .subject(String.valueOf(user.getId()))
                .claim("authorities", user.getRole())
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    public String generateLongLivedToken(UserEntity user) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("naqqa-auth")
                .issuedAt(now)
                .expiresAt(now.plus(jwtExpirationRemember, ChronoUnit.MINUTES))
                .subject(String.valueOf(user.getId()))
                .claim("authorities", user.getRole())
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
