package com.naqqa.auth.sso.web.filter;

import com.naqqa.auth.security.AccessTokenAuthenticationFilter;
import com.naqqa.auth.security.JsonSecurityHandlers;
import com.naqqa.auth.sso.admin.SsoTenantQuota;
import com.naqqa.auth.sso.admin.SsoTenantStore;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.store.MongoApiTokenStore;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.billing.Operation;
import com.naqqa.sso.billing.UsageMeter;
import com.naqqa.sso.ratelimit.Decision;
import com.naqqa.sso.ratelimit.LoginThrottle;
import com.naqqa.sso.scim.ScimJson;
import com.naqqa.sso.scim.ScimResponse;
import com.naqqa.sso.scim.ScimSchemas;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenPrincipal;
import com.naqqa.sso.tokens.ApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    private final ApiTokenService tokens;
    private final LoginThrottle throttle;
    private final SsoAudit audit;
    private final MongoApiTokenStore store;
    private final Clock clock;
    private final UsageMeter usage;
    private final SsoTenantStore tenants;
    private final SsoTenantQuota quota;

    public ApiTokenAuthenticationFilter(ApiTokenService tokens, LoginThrottle throttle, SsoAudit audit) {
        this(tokens, throttle, audit, null, Clock.systemUTC(), null, null);
    }

    public ApiTokenAuthenticationFilter(ApiTokenService tokens, LoginThrottle throttle, SsoAudit audit, MongoApiTokenStore store,
                                        Clock clock, UsageMeter usage, SsoTenantStore tenants) {
        this(tokens, throttle, audit, store, clock, usage, tenants, null);
    }

    public ApiTokenAuthenticationFilter(ApiTokenService tokens, LoginThrottle throttle, SsoAudit audit, MongoApiTokenStore store,
                                        Clock clock, UsageMeter usage, SsoTenantStore tenants, SsoTenantQuota quota) {
        this.tokens = tokens;
        this.throttle = throttle;
        this.audit = audit;
        this.store = store;
        this.clock = clock;
        this.usage = usage;
        this.tenants = tenants;
        this.quota = quota;
    }

    static boolean scim(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/scim/v2") || path.startsWith("/scim/v2/");
    }

    static void quotaExceeded(HttpServletRequest request, HttpServletResponse response, long retryAfter) throws IOException {
        response.setHeader("Retry-After", Long.toString(Math.max(1, retryAfter)));
        if (!scim(request)) {
            JsonSecurityHandlers.write(response, 429, "QUOTA_EXCEEDED", "Tenant request quota exceeded, retry later");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of(ScimSchemas.ERROR));
        body.put("detail", "Tenant request quota exceeded, retry later");
        body.put("status", "429");
        response.setStatus(429);
        response.setContentType("application/scim+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(ScimJson.write(new ScimResponse(429, body, Map.of())));
    }

    static String tokenKey(String presented) {
        String[] parts = presented.split("_", -1);
        if (parts.length == 6 && parts[3].length() == 16 && parts[3].matches("[A-Za-z0-9]+")) {
            return "api:" + parts[3];
        }
        return "api:malformed";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = AccessTokenAuthenticationFilter.bearer(request);
        if (presented == null || !presented.startsWith(AccessTokenAuthenticationFilter.API_TOKEN_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        String ip = SsoWeb.clientIp(request);
        String key = tokenKey(presented);
        Decision decision = throttle.check(key, ip, false);
        if (decision.type() != Decision.Type.ALLOW) {
            long seconds = Math.max(1, (decision.delayMillis() + 999) / 1000);
            response.setHeader("Retry-After", Long.toString(decision.type() == Decision.Type.CHALLENGE ? 60 : seconds));
            JsonSecurityHandlers.write(response, 429, "RATE_LIMITED", "Too many failed attempts, retry later");
            return;
        }
        ApiTokenPrincipal principal;
        try {
            principal = tokens.authenticate(presented, ip);
            if (principal.kind() != ApiTokenKind.SECRET) {
                throw new SsoException("token.kind_not_allowed");
            }
        } catch (SsoException e) {
            throttle.recordFailure(key, ip);
            audit.record("api_token.auth", null, key, "failure", Map.of("reason", e.code()), ip);
            if ("token.throttled".equals(e.code())) {
                response.setHeader("Retry-After", "60");
                JsonSecurityHandlers.write(response, 429, "RATE_LIMITED", "Too many failed attempts, retry later");
            } else {
                response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
                JsonSecurityHandlers.write(response, 401, "INVALID_API_TOKEN", "API token is invalid");
            }
            return;
        }
        throttle.recordSuccess(key, ip);
        if (tenants != null && tenants.find(principal.tenantId()).map(SsoTenantStore.Tenant::suspended).orElse(false)) {
            audit.record("api_token.auth", null, principal.id(), "denied", Map.of("reason", "tenant_suspended"), ip);
            JsonSecurityHandlers.write(response, 403, "TENANT_SUSPENDED", "This tenant is suspended");
            return;
        }
        if (quota != null) {
            SsoTenantQuota.Outcome outcome = quota.authRequest(principal.tenantId(), scim(request) ? Operation.PROVISION : Operation.API);
            if (outcome.blocked()) {
                quotaExceeded(request, response, outcome.retryAfterSeconds());
                return;
            }
        }
        if (store != null) {
            store.touch(principal.id(), clock.instant());
        }
        if (usage != null) {
            usage.recordAuthRequest(principal.tenantId());
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ApiTokenAuthentication(principal));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
