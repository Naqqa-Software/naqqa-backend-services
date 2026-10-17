package com.naqqa.auth.sso.web.filter;

import com.naqqa.sso.tokens.ApiTokenPrincipal;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

public class ApiTokenAuthentication extends AbstractAuthenticationToken {

    public static final String AUTHORITY = "API_TOKEN";

    private final ApiTokenPrincipal principal;

    public ApiTokenAuthentication(ApiTokenPrincipal principal) {
        super(authorities(principal));
        this.principal = principal;
        setAuthenticated(true);
    }

    private static List<GrantedAuthority> authorities(ApiTokenPrincipal principal) {
        List<GrantedAuthority> out = new ArrayList<>();
        out.add(new SimpleGrantedAuthority(AUTHORITY));
        for (String scope : principal.scopes()) {
            out.add(new SimpleGrantedAuthority("SCOPE_" + scope));
        }
        return out;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public ApiTokenPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return "api:" + principal.id();
    }
}
