package com.naqqa.auth.sso.support;

import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.security.NaqqaAuthenticationToken;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SsoWeb {

    public static final String CLIENT_ID_HEADER = "X-Client-Id";
    public static final String CLIENT_ID_PARAM = "client_id";
    public static final String PUBLISHABLE_KEY_HEADER = "X-SSO-Key";
    public static final String PUBLISHABLE_CLIENT_ATTRIBUTE = "naqqa.sso.publishable.client";
    public static final String PUBLISHABLE_TENANT_ATTRIBUTE = "naqqa.sso.publishable.tenant";

    private SsoWeb() {
    }

    public static String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? "" : ip;
    }

    public static String userAgent(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua == null) {
            return "";
        }
        return ua.length() > 512 ? ua.substring(0, 512) : ua;
    }

    public static String clientId(HttpServletRequest request) {
        if (request.getAttribute(PUBLISHABLE_CLIENT_ATTRIBUTE) instanceof String bound) {
            return bound;
        }
        String id = request.getHeader(CLIENT_ID_HEADER);
        if (id == null || id.isBlank()) {
            id = request.getParameter(CLIENT_ID_PARAM);
        }
        if (id == null || id.isBlank() || id.length() > 128 || !id.matches("[A-Za-z0-9._:-]+")) {
            return null;
        }
        return id;
    }

    public static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie c : request.getCookies()) {
            if (c.getName().equals(name)) {
                return c.getValue();
            }
        }
        return null;
    }

    public static NaqqaAuthenticationToken authentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof NaqqaAuthenticationToken t && t.isAuthenticated() ? t : null;
    }

    public static AuthPrincipal requirePrincipal() {
        NaqqaAuthenticationToken t = authentication();
        if (t == null) {
            throw SsoApiException.unauthorized("UNAUTHORIZED", "Authentication required");
        }
        return t.getPrincipal();
    }

    public static AuthPrincipal requireOwnSession() {
        AuthPrincipal p = requirePrincipal();
        if (p.isImpersonated()) {
            throw SsoApiException.forbidden("IMPERSONATION_FORBIDDEN", "Not allowed while impersonating");
        }
        return p;
    }

    public static boolean isSafeRelativeRedirect(String redirect) {
        if (redirect == null || redirect.isEmpty() || redirect.length() > 2048) {
            return false;
        }
        if (redirect.charAt(0) != '/') {
            return false;
        }
        if (redirect.length() > 1 && (redirect.charAt(1) == '/' || redirect.charAt(1) == '\\')) {
            return false;
        }
        for (int i = 0; i < redirect.length(); i++) {
            char c = redirect.charAt(i);
            if (c < 0x21 || c == 0x7f || c == '\\') {
                return false;
            }
        }
        String lower = redirect.toLowerCase(java.util.Locale.ROOT);
        return !lower.contains("%2f%2f") && !lower.startsWith("/%2f") && !lower.startsWith("/%5c") && !lower.contains("%0d") && !lower.contains("%0a");
    }
}
