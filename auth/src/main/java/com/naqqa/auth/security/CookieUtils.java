package com.naqqa.auth.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class CookieUtils {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    private static boolean isSecure;

    // Spring uses this non-static setter to inject the value into the static field.
    // It defaults to 'true' if the property is missing from application.properties.
    @Value("${app.cookie.secure:true}")
    public void setIsSecure(boolean secureValue) {
        CookieUtils.isSecure = secureValue;
    }

    public static void setCookie(HttpServletResponse response, String name, String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(name, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(isSecure);
        cookie.setPath("/");
        cookie.setMaxAge(maxAgeSeconds);

        // Note: Calling BOTH addCookie and addHeader creates duplicate Set-Cookie headers.
        // It's safer to just use addHeader if you need SameSite support.
        String sameSite = "Lax";
        String secureFlag = isSecure ? "; Secure" : "";

        String headerValue = String.format("%s=%s; Max-Age=%d; HttpOnly%s; SameSite=%s; Path=/",
                name, value, maxAgeSeconds, secureFlag, sameSite);

        response.addHeader("Set-Cookie", headerValue);
    }

    public static void clearCookie(HttpServletResponse response, String name) {
        Cookie cookie = new Cookie(name, null);
        cookie.setHttpOnly(true);
        cookie.setSecure(isSecure);
        cookie.setPath("/");
        cookie.setMaxAge(0);

        String secureFlag = isSecure ? "; Secure" : "";
        String headerValue = String.format("%s=; Max-Age=0; HttpOnly%s; SameSite=Lax; Path=/",
                name, secureFlag);

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