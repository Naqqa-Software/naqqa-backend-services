package com.naqqa.auth.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;

public class CookieUtils {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    public static void setCookie(HttpServletResponse response, String name, String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(name, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(true); // Ensure HTTPS in production
        cookie.setPath("/");
        cookie.setMaxAge(maxAgeSeconds);
        
        // For Spring Boot 3+, use ResponseCookie for better SameSite support if needed,
        // but adding the header manually is also robust.
        response.addCookie(cookie);
        String sameSite = "Lax";
        String headerValue = String.format("%s=%s; Max-Age=%d; HttpOnly; Secure; SameSite=%s; Path=/", 
                name, value, maxAgeSeconds, sameSite);
        response.addHeader("Set-Cookie", headerValue);
    }

    public static void clearCookie(HttpServletResponse response, String name) {
        Cookie cookie = new Cookie(name, null);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        
        String headerValue = String.format("%s=; Max-Age=0; HttpOnly; Secure; SameSite=Lax; Path=/", name);
        response.addHeader("Set-Cookie", headerValue);
    }

    public static String getCookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .map(Cookie::getValue)
                .orElse(null);
    }
}
