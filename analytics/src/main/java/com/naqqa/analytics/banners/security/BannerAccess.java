package com.naqqa.analytics.banners.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record BannerAccess(String userId, Set<String> authorities) {

    public static BannerAccess of(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            return new BannerAccess(null, Set.of());
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        String name = authentication.getName();
        return new BannerAccess(name == null || name.isBlank() ? null : name, authorities);
    }

    public boolean has(String authority) {
        return userId != null && authority != null && authorities.contains(authority);
    }

    public boolean loggedIn() {
        return userId != null;
    }
}
