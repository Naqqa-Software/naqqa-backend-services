package com.naqqa.auth.security;

import com.naqqa.auth.sso.support.SsoErrorBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class JsonSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
        Object error = request.getAttribute(AccessTokenAuthenticationFilter.ERROR_ATTRIBUTE);
        String header = error == null ? "Bearer" : "Bearer error=\"invalid_token\"";
        response.setHeader("WWW-Authenticate", header);
        write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Access denied");
    }

    public static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(SsoErrorBody.json(code, message));
    }
}
