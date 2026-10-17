package com.naqqa.auth.sso.web.filter;

import com.naqqa.auth.security.JsonSecurityHandlers;
import com.naqqa.auth.security.NaqqaAuthenticationToken;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.session.CsrfTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class SsoCsrfFilter extends OncePerRequestFilter {

    static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    public static final Set<String> EXEMPT_SUFFIXES = Set.of("/login", "/refresh", "/logout", "/register", "/confirm-email",
            "/forgot-password", "/reset-password", "/change-password");

    private final CsrfTokens csrf;
    private final SsoSessionManager sessions;
    private final Set<String> exempt;

    public SsoCsrfFilter(CsrfTokens csrf, SsoSessionManager sessions, String basePath) {
        this.csrf = csrf;
        this.sessions = sessions;
        this.exempt = exemptPaths(basePath);
    }

    public static Set<String> exemptPaths(String basePath) {
        String base = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        Set<String> paths = new HashSet<>();
        for (String suffix : EXEMPT_SUFFIXES) {
            paths.add(base + suffix);
        }
        return Set.copyOf(paths);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        NaqqaAuthenticationToken auth = SsoWeb.authentication();
        if (auth != null && auth.getSource() == NaqqaAuthenticationToken.Source.COOKIE
                && !SAFE_METHODS.contains(request.getMethod())
                && !exempt.contains(request.getRequestURI().substring(request.getContextPath().length()))) {
            String binding = sessions.csrfBinding(auth.getPrincipal());
            String cookie = SsoWeb.cookie(request, SsoSessionManager.CSRF_COOKIE);
            String header = request.getHeader(SsoSessionManager.CSRF_HEADER);
            if (!csrf.verifyDoubleSubmit(binding, cookie, header)) {
                JsonSecurityHandlers.write(response, 403, "CSRF_INVALID", "Missing or invalid CSRF token");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
