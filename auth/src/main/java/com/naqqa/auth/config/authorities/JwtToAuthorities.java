package com.naqqa.auth.config.authorities;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converts a JWT object into a collection of Spring Security GrantedAuthority objects.
 * Assumes the JWT contains a claim named 'authorities' which is a list of strings
 * corresponding to your seeded authorities (e.g., "user:read_all").
 */
public class JwtToAuthorities implements Converter<Jwt, Collection<GrantedAuthority>> {

    public static final String AUTHORITY_CLAIM_NAME = "authorities"; // Check your JWT claim name

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object authoritiesClaim = jwt.getClaims().get(AUTHORITY_CLAIM_NAME);

        if (authoritiesClaim instanceof List<?> authoritiesList) {
            return authoritiesList.stream()
                    .filter(String.class::isInstance)
                    .map(authority -> new SimpleGrantedAuthority((String) authority))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}