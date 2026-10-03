package com.naqqa.analytics.spi;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;

public interface AnalyticsPrincipalResolver {

    AnalyticsPrincipalResolver NONE = new AnalyticsPrincipalResolver() {
    };

    default String userId(Authentication authentication) {
        if (!authenticated(authentication)) {
            return null;
        }
        String name = authentication.getName();
        return name == null || name.isBlank() ? null : name;
    }

    default boolean isInternal(Authentication authentication) {
        return false;
    }

    default boolean loggedIn(Authentication authentication) {
        return userId(authentication) != null;
    }

    static boolean authenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
