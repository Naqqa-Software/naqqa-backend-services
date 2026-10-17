package com.naqqa.auth.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public class NaqqaAuthenticationToken extends AbstractAuthenticationToken {

    public enum Source {
        HEADER, COOKIE
    }

    private final AuthPrincipal principal;
    private final Source source;

    public NaqqaAuthenticationToken(AuthPrincipal principal, Collection<? extends GrantedAuthority> authorities, Source source) {
        super(authorities);
        this.principal = principal;
        this.source = source;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return principal.getTokenValue();
    }

    @Override
    public AuthPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.getSubject();
    }

    public Source getSource() {
        return source;
    }
}
