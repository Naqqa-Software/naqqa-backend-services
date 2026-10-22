package com.naqqa.registry.spi;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public class SecurityContextAdminAccess implements RegistryAdminAccess {

    private final String readAuthority;
    private final String manageAuthority;

    public SecurityContextAdminAccess(String readAuthority, String manageAuthority) {
        this.readAuthority = readAuthority;
        this.manageAuthority = manageAuthority;
    }

    @Override
    public Optional<String> currentAdmin() {
        return authentication().map(Authentication::getName).filter(name -> !name.isBlank());
    }

    @Override
    public boolean canRead() {
        return has(readAuthority) || has(manageAuthority);
    }

    @Override
    public boolean canManage() {
        return has(manageAuthority);
    }

    private boolean has(String authority) {
        if (authority == null || authority.isBlank()) {
            return false;
        }
        return authentication().map(a -> a.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(authority::equals))
                .orElse(false);
    }

    private static Optional<Authentication> authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return Optional.of(authentication);
    }
}
