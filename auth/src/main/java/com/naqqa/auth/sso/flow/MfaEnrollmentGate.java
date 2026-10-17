package com.naqqa.auth.sso.flow;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.support.SsoApiException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class MfaEnrollmentGate {

    public record Route(String method, String pattern) {

        boolean matches(String requestMethod, String path) {
            return (method == null || method.equalsIgnoreCase(requestMethod)) && MATCHER.match(pattern, path);
        }
    }

    static final AntPathMatcher MATCHER = new AntPathMatcher();
    static final String REGENERATE = "/mfa/recovery/regenerate";

    private final AssuranceEngine assurance;
    private final SsoLoginFlow loginFlow;
    private final SsoClientPolicy clientPolicy;
    private final SsoAudit audit;
    private final String base;
    private final List<Route> routes;
    private final List<Route> profileRoutes;

    public MfaEnrollmentGate(AssuranceEngine assurance, SsoLoginFlow loginFlow, SsoClientPolicy clientPolicy,
                             SsoAudit audit, SsoProperties properties, @Value("${auth.api.base-path:/api/auth}") String basePath) {
        this.assurance = assurance;
        this.loginFlow = loginFlow;
        this.clientPolicy = clientPolicy;
        this.audit = audit;
        this.base = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        this.routes = routes(base, properties.getMfa().getEnrollmentAllowedRoutes());
        this.profileRoutes = configured(properties.getMfa().getEnrollmentProfileRoutes());
        SsoProperties.Mfa mfa = properties.getMfa();
        if ((mfa.isRequiredForAdmin() || mfa.isRequiredForAll() || !mfa.getRequiredRoles().isEmpty())
                && !properties.getTotp().isEnabled() && !properties.getPasskey().isEnabled() && !properties.getBiometric().isEnabled()
                && !properties.getSecurityKey().isEnabled()) {
            log.warn("naqqa.sso.mfa requires a second factor but no enrollable factor (totp, passkey, biometric, security-key) is enabled: "
                    + "accounts without a factor sign in unrestricted");
        }
    }

    public static List<Route> routes(String base, List<String> configured) {
        List<Route> out = new ArrayList<>(List.of(
                new Route("GET", base + "/methods"),
                new Route("GET", base + "/mfa/factors"),
                new Route("POST", base + "/mfa/totp/enroll"),
                new Route("POST", base + "/mfa/totp/confirm"),
                new Route("POST", base + "/mfa/hotp/enroll"),
                new Route("POST", base + "/mfa/hotp/confirm"),
                new Route("POST", base + "/webauthn/register/options"),
                new Route("POST", base + "/webauthn/register/verify"),
                new Route("GET", base + "/webauthn/credentials"),
                new Route("POST", base + REGENERATE),
                new Route("POST", base + "/step-up"),
                new Route("POST", base + "/logout"),
                new Route("POST", base + "/refresh"),
                new Route("GET", base + "/sessions"),
                new Route("GET", "/.well-known/**"),
                new Route("GET", "/oauth2/jwks"),
                new Route("GET", "/saml2/idp/metadata"),
                new Route("GET", "/saml2/sp/*/metadata")));
        out.addAll(configured(configured));
        return List.copyOf(out);
    }

    static List<Route> configured(List<String> configured) {
        List<Route> out = new ArrayList<>();
        if (configured != null) {
            for (String entry : configured) {
                Route r = parse(entry);
                if (r != null) {
                    out.add(r);
                }
            }
        }
        return List.copyOf(out);
    }

    static Route parse(String entry) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        String[] parts = entry.trim().split("\\s+", 2);
        if (parts.length == 2) {
            return new Route(parts[0].toUpperCase(Locale.ROOT), parts[1]);
        }
        return new Route(null, parts[0]);
    }

    public List<Route> routes() {
        return routes;
    }

    public boolean flagsProfile(AuthPrincipal principal, HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        for (Route r : profileRoutes) {
            if (r.matches(request.getMethod(), path)) {
                return assurance.enrollmentPending(principal);
            }
        }
        return false;
    }

    public boolean allows(AuthPrincipal principal, HttpServletRequest request) {
        if (!assurance.enrollmentPending(principal)) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        for (Route r : routes) {
            if (r.matches(method, path)) {
                if (path.equals(base + REGENERATE)) {
                    return principal.getSubject() != null && AssuranceEngine.hasEnrolledFactor(assurance.enrolled(principal.getSubject()));
                }
                return true;
            }
        }
        return false;
    }

    public boolean onRefresh(UserEntity user, String sessionId, String clientId) {
        if (user == null) {
            return true;
        }
        boolean required = loginFlow.enrollmentRequired(user, policyFor(clientId));
        String userId = String.valueOf(user.getId());
        if (sessionId == null) {
            if (required) {
                audit.record("token.refresh.legacy", userId, userId, "denied", Map.of("reason", "mfa_enrollment_required"));
            }
            return !required;
        }
        boolean flagged = assurance.sessionRestricted(sessionId);
        if (required && !flagged) {
            assurance.restrictSession(sessionId);
            audit.record("mfa.enrollment.required", userId, userId, "success", Map.of("source", "refresh"));
        } else if (!required && flagged) {
            assurance.releaseSession(sessionId);
            audit.record("mfa.enrollment.released", userId, userId, "success", Map.of("source", "refresh"));
        }
        return true;
    }

    private SsoClientPolicy.Policy policyFor(String clientId) {
        try {
            return clientPolicy.forClient(clientId);
        } catch (SsoApiException e) {
            return clientPolicy.forClient(null);
        }
    }
}
