package com.naqqa.auth.security;

import com.naqqa.auth.sso.SsoProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

public class StepUpInterceptor implements HandlerInterceptor {

    private final SsoProperties properties;
    private final Clock clock;
    private final Supplier<StepUpFactorPolicy> factorPolicy;

    public StepUpInterceptor(SsoProperties properties, Clock clock) {
        this(properties, clock, () -> null);
    }

    public StepUpInterceptor(SsoProperties properties, Clock clock, Supplier<StepUpFactorPolicy> factorPolicy) {
        this.properties = properties;
        this.clock = clock;
        this.factorPolicy = factorPolicy;
    }

    public static RequiresStepUp find(Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return null;
        }
        RequiresStepUp a = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequiresStepUp.class);
        return a != null ? a : AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RequiresStepUp.class);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        RequiresStepUp annotation = find(handler);
        if (annotation == null || !properties.isEnabled()) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof NaqqaAuthenticationToken token) || !token.isAuthenticated()) {
            if (auth != null && auth.isAuthenticated() && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
                JsonSecurityHandlers.write(response, 403, "INTERACTIVE_LOGIN_REQUIRED", "This operation requires an interactive session");
                return false;
            }
            response.setHeader("WWW-Authenticate", "Bearer");
            JsonSecurityHandlers.write(response, 401, "UNAUTHORIZED", "Authentication required");
            return false;
        }
        AuthPrincipal principal = token.getPrincipal();
        if (principal.isImpersonated() && !annotation.allowImpersonation()) {
            JsonSecurityHandlers.write(response, 403, "IMPERSONATION_FORBIDDEN", "Not allowed while impersonating");
            return false;
        }
        Duration maxAge = annotation.maxAge().isBlank() ? properties.getMfa().getStepUpMaxAge() : Duration.parse(annotation.maxAge());
        Instant authTime = principal.getAuthTime();
        boolean fresh = authTime != null && Duration.between(authTime, clock.instant()).compareTo(maxAge) <= 0;
        StepUpFactorPolicy policy = factorPolicy.get();
        boolean strong = annotation.acr().isBlank() || (policy != null && policy.acrSatisfied(principal, annotation.acr()));
        boolean factor = annotation.anyFactor() || policy == null || policy.satisfied(principal);
        if (!fresh || !strong || !factor) {
            response.setHeader("WWW-Authenticate", "Bearer error=\"insufficient_user_authentication\", max_age=" + maxAge.getSeconds()
                    + (annotation.acr().isBlank() ? "" : ", acr_values=\"" + annotation.acr() + "\""));
            JsonSecurityHandlers.write(response, 401, "STEP_UP_REQUIRED", "Fresh authentication required");
            return false;
        }
        return true;
    }
}
