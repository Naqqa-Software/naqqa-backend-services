package com.naqqa.auth.sso.saml;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.security.NaqqaAuthenticationToken;
import com.naqqa.auth.security.RequiresStepUp;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.admin.SsoTenantQuota;
import com.naqqa.auth.sso.flow.AccountLinkService;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoClientPolicy;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.flow.SsoUsers;
import com.naqqa.auth.sso.store.MongoRateLimitStore;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.billing.Operation;
import com.naqqa.sso.ratelimit.Limit;
import com.naqqa.sso.ratelimit.SlidingWindowLimiter;
import com.naqqa.sso.saml.sp.OutboundAuthnRequest;
import com.naqqa.sso.saml.sp.SamlPrincipal;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(prefix = "naqqa.sso", name = {"enabled", "saml.sp.enabled"}, havingValue = "true")
public class SamlSpController {

    public static final String BINDING_COOKIE = "sso_saml_sp";
    public static final String FLOW_KIND = "saml-sp-flow";
    public static final String ACS_PATTERN = "/saml2/sp/[a-z0-9][a-z0-9-]{1,62}/acs";
    static final String TENANT_PATTERN = "[a-z0-9][a-z0-9-]{1,62}";

    private final SamlSpIdpStore idps;
    private final SamlSpService saml;
    private final SamlSpAccounts accounts;
    private final SsoTicketStore tickets;
    private final SsoClientPolicy clientPolicy;
    private final SsoLoginFlow loginFlow;
    private final SsoAudit audit;
    private final ObjectProvider<SsoTenantQuota> quota;
    private final AccountLinkService links;
    private final SsoUsers users;
    private final SlidingWindowLimiter limiter;
    private final String successBase;
    private final String failureUrl;

    public SamlSpController(SamlSpIdpStore idps, SamlSpService saml, SamlSpAccounts accounts, SsoTicketStore tickets,
                            SsoClientPolicy clientPolicy, SsoLoginFlow loginFlow, SsoAudit audit, ObjectProvider<SsoTenantQuota> quota,
                            AccountLinkService links, SsoUsers users, MongoRateLimitStore limits, Clock clock, SsoProperties properties,
                            @Value("${frontend.url:}") String frontendUrl) {
        this.idps = idps;
        this.saml = saml;
        this.accounts = accounts;
        this.tickets = tickets;
        this.clientPolicy = clientPolicy;
        this.loginFlow = loginFlow;
        this.audit = audit;
        this.quota = quota;
        this.links = links;
        this.users = users;
        SsoProperties.Limit l = properties.getRateLimits().getSamlSp();
        this.limiter = new SlidingWindowLimiter(limits, clock, l.getMax(), l.getWindow());
        String front = frontendUrl == null ? "" : frontendUrl.replaceAll("/+$", "");
        String s = properties.getSaml().getSp().getSuccessUrl();
        this.successBase = s != null && !s.isBlank() ? s.replaceAll("/+$", "") : front;
        String f = properties.getSaml().getSp().getFailureUrl();
        this.failureUrl = f != null && !f.isBlank() ? f : front + "/login";
    }

    static String hash(String value) {
        return Bytes.b64url(Hashes.sha256(Bytes.utf8(value)));
    }

    private void limit(HttpServletRequest request) {
        Limit l = limiter.tryAcquire("saml-sp-ip:" + SsoWeb.clientIp(request));
        if (!l.allowed()) {
            throw SsoApiException.rateLimited((l.retryAfterMillis() + 999) / 1000);
        }
    }

    private SamlSpIdpStore.IdpSettings idp(String tenant) {
        checkTenant(tenant);
        return idps.forLogin(tenant);
    }

    static void checkTenant(String tenant) {
        if (tenant == null || !tenant.matches(TENANT_PATTERN)) {
            throw SsoApiException.notFound("SAML_IDP_UNKNOWN", "Enterprise sign-in is not configured for this organization");
        }
    }

    static void bindingCookie(HttpServletResponse response, String tenant, String value, int maxAge) {
        response.addHeader("Set-Cookie", String.format("%s=%s; Max-Age=%d; HttpOnly; Secure; SameSite=None; Path=%s",
                BINDING_COOKIE, value, maxAge, SamlSpService.PATH + tenant + "/acs"));
    }

    @PostMapping("${auth.api.base-path:/api/auth}/account/links/saml/{tenant}/start")
    @RequiresStepUp
    public Map<String, Object> startLink(@PathVariable String tenant, HttpServletRequest request) {
        limit(request);
        AuthPrincipal principal = SsoWeb.requireOwnSession();
        UserEntity user = users.require(principal);
        SamlSpIdpStore.IdpSettings idp = idp(tenant);
        if (!clientPolicy.forRequest(request).samlSp()) {
            throw SsoApiException.disabled("samlSp");
        }
        if (principal.getSessionId() == null) {
            throw SsoApiException.unauthorized("SESSION_REQUIRED", "Sign in again");
        }
        String refusal = accounts.linkRefusal(idp, user);
        if (refusal != null) {
            audit.record("saml.sp.link", String.valueOf(user.getId()), idp.tenantId(), "denied", Map.of("tenant", idp.tenantId(), "reason", refusal));
            throw "already_linked".equals(refusal)
                    ? new SsoApiException(HttpStatus.CONFLICT, "IDENTITY_ALREADY_LINKED", "This account is already linked to this organization")
                    : SsoApiException.forbidden("LINK_NOT_ALLOWED", "This account cannot be linked to this organization");
        }
        String intent = links.startIntent(String.valueOf(user.getId()), principal.getSessionId(), idp.provider());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("linkIntent", intent);
        out.put("provider", idp.provider());
        out.put("url", saml.loginUrl(idp.tenantId()) + "?link=" + encode(intent));
        return out;
    }

    @GetMapping("/saml2/sp/{tenant}/login")
    public ResponseEntity<Void> login(@PathVariable String tenant, @RequestParam(name = "relay", required = false) String relay,
                                      @RequestParam(name = "link", required = false) String link,
                                      HttpServletRequest request, HttpServletResponse response) {
        limit(request);
        SamlSpIdpStore.IdpSettings idp = idp(tenant);
        SsoClientPolicy.Policy policy = clientPolicy.forRequest(request);
        if (!policy.samlSp()) {
            throw SsoApiException.disabled("samlSp");
        }
        String target = relay == null || relay.isEmpty() ? "/" : relay;
        if (!SsoWeb.isSafeRelativeRedirect(target)) {
            throw SsoApiException.badRequest("RELAY_INVALID", "The return path must be a relative path");
        }
        String linkUser = null;
        String linkSession = null;
        if (link != null) {
            NaqqaAuthenticationToken auth = SsoWeb.authentication();
            if (auth == null || auth.getPrincipal().isImpersonated() || auth.getPrincipal().getSessionId() == null) {
                throw SsoApiException.unauthorized("UNAUTHORIZED", "Authentication required");
            }
            linkUser = String.valueOf(auth.getPrincipal().getUserId());
            linkSession = auth.getPrincipal().getSessionId();
            SsoTicketStore.Ticket intent = links.claim(link, linkUser, linkSession);
            if (!idp.provider().equals(intent.data().get("provider"))) {
                throw SsoApiException.badRequest("LINK_PROVIDER_MISMATCH", "Provider does not match");
            }
        }
        SsoTenantQuota q = quota.getIfAvailable();
        if (q != null) {
            q.authRequest(idp.tenantId(), Operation.SIGN_IN);
        }
        OutboundAuthnRequest outbound;
        try {
            outbound = saml.sp(idp).createAuthnRequest(null);
        } catch (SsoException e) {
            audit.record("saml.sp.start", null, idp.tenantId(), "failure", Map.of("tenant", idp.tenantId(), "reason", e.code()));
            throw SsoApiException.notConfigured("saml-sp");
        }
        String binding = Bytes.randomToken(32);
        Map<String, String> flow = new LinkedHashMap<>();
        flow.put("tenant", idp.tenantId());
        flow.put("requestId", outbound.id());
        flow.put("relay", target);
        if (policy.clientId() != null) {
            flow.put("clientId", policy.clientId());
        }
        if (linkUser != null) {
            flow.put("link", linkUser);
            flow.put("sid", linkSession);
        }
        tickets.put(FLOW_KIND, hash(binding), linkUser, flow, saml.requestTtl());
        bindingCookie(response, idp.tenantId(), binding, (int) saml.requestTtl().getSeconds());
        audit.record("saml.sp.start", linkUser, idp.tenantId(), "pending", Map.of("tenant", idp.tenantId(),
                "mode", linkUser == null ? "login" : "link"));
        return redirect(outbound.redirectUrl());
    }

    @PostMapping("/saml2/sp/{tenant}/acs")
    public ResponseEntity<Void> acs(@PathVariable String tenant, HttpServletRequest request, HttpServletResponse response) {
        limit(request);
        SamlSpIdpStore.IdpSettings idp;
        try {
            idp = idp(tenant);
        } catch (SsoApiException e) {
            audit.record("saml.sp.acs", null, String.valueOf(tenant), "failure", Map.of("reason", "idp_unknown"));
            return redirect(failure("SAML_FAILED"));
        }
        String binding = SsoWeb.cookie(request, BINDING_COOKIE);
        if (binding != null) {
            bindingCookie(response, idp.tenantId(), "", 0);
        }
        String[] responses = request.getParameterValues("SAMLResponse");
        String[] relays = request.getParameterValues("RelayState");
        if (responses == null || responses.length != 1 || (relays != null && relays.length != 1)) {
            audit.record("saml.sp.acs", null, idp.tenantId(), "failure", Map.of("tenant", idp.tenantId(), "reason", "binding"));
            return redirect(failure("SAML_FAILED"));
        }
        SsoTicketStore.Ticket flow = binding == null ? null : tickets.take(FLOW_KIND, hash(binding)).orElse(null);
        if (binding != null && (flow == null || !idp.tenantId().equals(flow.data().get("tenant")))) {
            audit.record("saml.sp.acs", null, idp.tenantId(), "failure", Map.of("tenant", idp.tenantId(), "reason", "flow_missing"));
            return redirect(failure("SAML_FAILED"));
        }
        String expected = flow == null ? null : flow.data().get("requestId");
        SamlPrincipal principal;
        try {
            principal = saml.sp(idp).verifyResponse(responses[0], expected);
        } catch (SsoException e) {
            audit.record("saml.sp.acs", null, idp.tenantId(), "failure", Map.of("tenant", idp.tenantId(), "reason", e.code(),
                    "solicited", Boolean.toString(expected != null)));
            return redirect(failure("SAML_FAILED"));
        }
        String relay;
        if (flow != null) {
            relay = flow.data().getOrDefault("relay", "/");
        } else {
            String r = relays == null ? null : relays[0];
            relay = r != null && SsoWeb.isSafeRelativeRedirect(r) ? r : "/";
        }
        String linkUser = flow == null ? null : flow.data().get("link");
        if (linkUser != null) {
            try {
                accounts.link(idp, principal, linkUser, flow.data().get("sid"));
                return redirect(successBase + relay + (relay.contains("?") ? "&" : "?") + "linked=" + encode(idp.provider()));
            } catch (SsoApiException e) {
                return redirect(failure(e.getCode()));
            }
        }
        String clientId = flow == null ? null : flow.data().get("clientId");
        try {
            SsoClientPolicy.Policy policy = clientPolicy.forClient(clientId);
            if (!policy.samlSp()) {
                throw SsoApiException.disabled("samlSp");
            }
            SsoLoginFlow.Context base = loginFlow.context(request, response, null, "web", false);
            SsoLoginFlow.Context ctx = new SsoLoginFlow.Context(policy, null, "web", false, base.deviceToken(), base.ip(),
                    base.userAgent(), base.rememberToken());
            Map<String, Object> result = accounts.signIn(idp, principal, ctx, response);
            if ("mfa_required".equals(result.get("status"))) {
                Object methods = result.get("methods");
                return redirect(failureUrl + "#mfaToken=" + encode(String.valueOf(result.get("mfaToken")))
                        + "&methods=" + encode(methods instanceof List<?> list ? String.join(",", list.stream().map(String::valueOf).toList()) : "")
                        + "&redirect=" + encode(relay));
            }
            return redirect(SsoLoginFlow.withEnrollmentMarker(successBase + relay, result));
        } catch (SsoApiException e) {
            return redirect(failure(e.getCode()));
        }
    }

    @GetMapping(value = "/saml2/sp/{tenant}/metadata", produces = "application/samlmetadata+xml")
    public ResponseEntity<String> metadata(@PathVariable String tenant) {
        checkTenant(tenant);
        SamlSpIdpStore.IdpSettings idp = idps.forMetadata(tenant);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/samlmetadata+xml"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(saml.metadata(idp));
    }

    private String failure(String code) {
        return failureUrl + (failureUrl.contains("?") ? "&" : "?") + "error=" + encode(code);
    }

    static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
