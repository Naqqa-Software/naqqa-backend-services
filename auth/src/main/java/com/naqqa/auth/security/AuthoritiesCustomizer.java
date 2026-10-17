package com.naqqa.auth.security;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

@FunctionalInterface
public interface AuthoritiesCustomizer {
    Collection<? extends GrantedAuthority> customize(AuthPrincipal principal, Collection<GrantedAuthority> authorities);
}
