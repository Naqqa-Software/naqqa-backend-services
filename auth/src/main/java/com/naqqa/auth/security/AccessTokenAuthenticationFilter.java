package com.naqqa.auth.security;

import com.naqqa.auth.config.authorities.JwtToAuthorities;
import com.naqqa.auth.service.security.AccessTokenVerifier;
import com.naqqa.sso.SsoException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class AccessTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String ERROR_ATTRIBUTE = AccessTokenAuthenticationFilter.class.getName() + ".error";
    public static final String API_TOKEN_PREFIX = "sso_";
    public static final String ENROLLMENT_REQUIRED = "MFA_ENROLLMENT_REQUIRED";
    public static final String ENROLLMENT_MESSAGE = "Add a second factor before using this account";

    private final AccessTokenVerifier verifier;
    private final ObjectProvider<AuthoritiesCustomizer> customizers;
    private final ObjectProvider<AccessSessionGuard> sessionGuard;

    public AccessTokenAuthenticationFilter(AccessTokenVerifier verifier, ObjectProvider<AuthoritiesCustomizer> customizers,
                                           ObjectProvider<AccessSessionGuard> sessionGuard) {
        this.verifier = verifier;
        this.customizers = customizers;
        this.sessionGuard = sessionGuard;
    }

    public static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing == null || existing instanceof AnonymousAuthenticationToken || !existing.isAuthenticated()) {
            authenticate(request);
        }
        if (!enrollmentGateAllows(request)) {
            JsonSecurityHandlers.write(response, HttpServletResponse.SC_FORBIDDEN, ENROLLMENT_REQUIRED, ENROLLMENT_MESSAGE);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean enrollmentGateAllows(HttpServletRequest request) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (!(current instanceof NaqqaAuthenticationToken token)) {
            return true;
        }
        AccessSessionGuard guard = sessionGuard.getIfAvailable();
        return guard == null || guard.enrollmentGateAllows(token.getPrincipal(), request);
    }

    private void authenticate(HttpServletRequest request) {
        String token;
        NaqqaAuthenticationToken.Source source;
        String header = request.getHeader("Authorization");
        if (header != null) {
            token = bearer(request);
            if (token == null || token.startsWith(API_TOKEN_PREFIX)) {
                return;
            }
            source = NaqqaAuthenticationToken.Source.HEADER;
        } else {
            token = CookieUtils.getCookieValue(request, CookieUtils.ACCESS_TOKEN_COOKIE);
            source = NaqqaAuthenticationToken.Source.COOKIE;
        }
        if (token == null || token.isEmpty()) {
            return;
        }
        AuthPrincipal principal;
        Collection<GrantedAuthority> tokenAuthorities;
        try {
            principal = verifier.verify(token);
            tokenAuthorities = JwtToAuthorities.fromClaims(principal.getClaims());
        } catch (SsoException e) {
            request.setAttribute(ERROR_ATTRIBUTE, e.code());
            return;
        } catch (RuntimeException e) {
            request.setAttribute(ERROR_ATTRIBUTE, "token.invalid");
            return;
        }
        AccessSessionGuard guard = sessionGuard.getIfAvailable();
        if (guard != null && !guard.isActive(principal)) {
            request.setAttribute(ERROR_ATTRIBUTE, "session.revoked");
            return;
        }
        Collection<GrantedAuthority> authorities = new ArrayList<>(tokenAuthorities);
        for (AuthoritiesCustomizer customizer : customizers.orderedStream().toList()) {
            Collection<? extends GrantedAuthority> next = customizer.customize(principal, List.copyOf(authorities));
            authorities = next == null ? new ArrayList<>() : new ArrayList<>(next);
        }
        NaqqaAuthenticationToken authentication = new NaqqaAuthenticationToken(principal, authorities, source);
        authentication.setDetails(request.getRemoteAddr());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
