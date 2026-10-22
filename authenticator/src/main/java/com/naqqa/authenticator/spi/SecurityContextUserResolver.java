package com.naqqa.authenticator.spi;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public class SecurityContextUserResolver implements AuthenticatorUserResolver {

    @Override
    public Optional<String> currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        String name = authentication.getName();
        return name == null || name.isBlank() ? Optional.empty() : Optional.of(name);
    }
}
