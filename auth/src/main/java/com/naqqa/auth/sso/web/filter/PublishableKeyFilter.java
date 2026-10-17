package com.naqqa.auth.sso.web.filter;

import com.naqqa.auth.security.JsonSecurityHandlers;
import com.naqqa.auth.sso.admin.SsoClientSettingsStore;
import com.naqqa.auth.sso.admin.SsoTenantStore;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoClientPolicy;
import com.naqqa.auth.sso.store.MongoApiTokenStore;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenPrincipal;
import com.naqqa.sso.tokens.ApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PublishableKeyFilter extends OncePerRequestFilter {

    public static final String PREFIX = "sso_pk_";

    private final ApiTokenService tokens;
    private final MongoApiTokenStore store;
    private final SsoTenantStore tenants;
    private final SsoClientPolicy policy;
    private final SsoAudit audit;
    private final Clock clock;
    private final List<String> prefixes;

    public PublishableKeyFilter(ApiTokenService tokens, MongoApiTokenStore store, SsoTenantStore tenants, SsoClientPolicy policy,
                                SsoAudit audit, Clock clock, String basePath) {
        this.tokens = tokens;
        this.store = store;
        this.tenants = tenants;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
        String base = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        this.prefixes = List.of(base + "/");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getHeader(SsoWeb.PUBLISHABLE_KEY_HEADER) == null || !SsoCorsFilter.inScope(request, prefixes)
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    private boolean sameTenant(String clientId, String tenantId) {
        Optional<SsoClientSettingsStore.Settings> s = policy.settings(clientId);
        return s.map(x -> tenantId.equals(x.tenantId())).orElse(true);
    }

    private void deny(HttpServletResponse response, int status, String code, String message, String keyId, String reason, String ip)
            throws IOException {
        audit.record("publishable_key.check", null, keyId, "failure", Map.of("reason", reason), ip);
        JsonSecurityHandlers.write(response, status, code, message);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(SsoWeb.PUBLISHABLE_KEY_HEADER).trim();
        String ip = SsoWeb.clientIp(request);
        if (!presented.startsWith(PREFIX)) {
            deny(response, 401, "PUBLISHABLE_KEY_INVALID", "Publishable key is invalid", null, "not_publishable", ip);
            return;
        }
        ApiTokenPrincipal key;
        try {
            key = tokens.authenticate(presented, ip);
        } catch (SsoException e) {
            if ("token.throttled".equals(e.code())) {
                response.setHeader("Retry-After", "60");
                JsonSecurityHandlers.write(response, 429, "RATE_LIMITED", "Too many failed attempts, retry later");
                return;
            }
            deny(response, 401, "PUBLISHABLE_KEY_INVALID", "Publishable key is invalid", null, e.code(), ip);
            return;
        }
        String clientId = store.extras(key.id()).clientId();
        if (key.kind() != ApiTokenKind.PUBLISHABLE || clientId == null || !sameTenant(clientId, key.tenantId())) {
            deny(response, 401, "PUBLISHABLE_KEY_INVALID", "Publishable key is invalid", key.id(), "unbound", ip);
            return;
        }
        if (tenants.find(key.tenantId()).map(SsoTenantStore.Tenant::suspended).orElse(false)) {
            deny(response, 403, "TENANT_SUSPENDED", "This tenant is suspended", key.id(), "tenant_suspended", ip);
            return;
        }
        String headerClient = request.getHeader(SsoWeb.CLIENT_ID_HEADER);
        if (headerClient != null && !headerClient.equals(clientId)) {
            deny(response, 400, "CLIENT_MISMATCH", "X-Client-Id does not match the publishable key", key.id(), "client_mismatch", ip);
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin == null || !policy.allowedOrigins(clientId).contains(origin)) {
            deny(response, 403, "PUBLISHABLE_KEY_ORIGIN_DENIED", "This origin is not allowed for the publishable key", key.id(),
                    "origin", ip);
            return;
        }
        store.touch(key.id(), clock.instant());
        request.setAttribute(SsoWeb.PUBLISHABLE_CLIENT_ATTRIBUTE, clientId);
        request.setAttribute(SsoWeb.PUBLISHABLE_TENANT_ATTRIBUTE, key.tenantId());
        chain.doFilter(request, response);
    }
}
