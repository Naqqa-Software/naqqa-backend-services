package com.naqqa.auth.sso.web.filter;

import com.naqqa.auth.sso.admin.SsoAdminValidation;
import com.naqqa.auth.sso.flow.SsoClientPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public class SsoCorsFilter extends OncePerRequestFilter {

    public static final String ALLOWED_METHODS = "GET, POST, PUT, PATCH, DELETE";
    public static final String ALLOWED_HEADERS = "Content-Type, Authorization, X-Client-Id, X-SSO-Key, X-XSRF-TOKEN, X-Device-Id";
    static final String MAX_AGE = "600";
    static final int MAX_REQUEST_HEADERS = 1024;

    private final SsoClientPolicy policy;
    private final List<String> prefixes;
    private final List<String> adminPrefixes = List.of("/api/sso/");

    public SsoCorsFilter(SsoClientPolicy policy, String basePath) {
        this.policy = policy;
        String base = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        this.prefixes = List.of(base + "/", "/api/sso/");
    }

    static boolean inScope(HttpServletRequest request, List<String> prefixes) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        for (String p : prefixes) {
            if (path.startsWith(p) || path.equals(p.substring(0, p.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    static String allowedHeaders(String requested) {
        if (requested == null || requested.isBlank() || requested.length() > MAX_REQUEST_HEADERS
                || !requested.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+(\\s*,\\s*[A-Za-z0-9!#$%&'*+.^_`|~-]+)*") || requested.contains("*")) {
            return ALLOWED_HEADERS;
        }
        return ALLOWED_HEADERS + ", " + requested.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getHeader("Origin") == null || !inScope(request, prefixes);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        Set<String> allowed = inScope(request, adminPrefixes) ? policy.configuredOrigins() : policy.corsOrigins();
        if (!SsoAdminValidation.isOrigin(origin) || !allowed.contains(origin)) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Credentials", "true");
        response.addHeader("Vary", "Origin");
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) && request.getHeader("Access-Control-Request-Method") != null) {
            response.setHeader("Access-Control-Allow-Methods", ALLOWED_METHODS);
            response.setHeader("Access-Control-Allow-Headers", allowedHeaders(request.getHeader("Access-Control-Request-Headers")));
            response.setHeader("Access-Control-Max-Age", MAX_AGE);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        chain.doFilter(request, response);
    }
}
