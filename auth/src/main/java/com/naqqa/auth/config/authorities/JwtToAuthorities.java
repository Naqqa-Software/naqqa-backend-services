package com.naqqa.auth.config.authorities;

import com.naqqa.auth.security.AuthPrincipal;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class JwtToAuthorities implements Converter<AuthPrincipal, Collection<GrantedAuthority>> {

    public static final String AUTHORITY_CLAIM_NAME = "authorities";

    @Override
    public Collection<GrantedAuthority> convert(AuthPrincipal principal) {
        return fromClaims(principal.getClaims());
    }

    public static Collection<GrantedAuthority> fromClaims(Map<String, Object> claims) {
        Object authoritiesClaim = claims.get(AUTHORITY_CLAIM_NAME);
        List<GrantedAuthority> out = new ArrayList<>();
        if (authoritiesClaim instanceof List<?> authoritiesList) {
            for (Object authority : authoritiesList) {
                if (authority instanceof String s) {
                    out.add(new SimpleGrantedAuthority(s));
                }
            }
        }
        return out;
    }
}
