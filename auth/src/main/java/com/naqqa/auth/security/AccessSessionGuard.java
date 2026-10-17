package com.naqqa.auth.security;

import jakarta.servlet.http.HttpServletRequest;

@FunctionalInterface
public interface AccessSessionGuard {
    boolean isActive(AuthPrincipal principal);

    default boolean enrollmentRequired(String sessionId) {
        return false;
    }

    default boolean enrollmentGateAllows(AuthPrincipal principal, HttpServletRequest request) {
        return true;
    }
}
