package com.naqqa.auth.sso.oidc;

import com.naqqa.auth.security.AccessTokenAuthenticationFilter;
import com.naqqa.auth.security.CookieUtils;
import com.naqqa.auth.service.security.RefreshTokenManager;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.support.SsoErrorBody;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.oidc.GrantTypes;
import com.naqqa.sso.oidc.Interaction;
import com.naqqa.sso.oidc.OidcError;
import com.naqqa.sso.oidc.OidcProvider;
import com.naqqa.sso.oidc.OidcRequest;
import com.naqqa.sso.oidc.OidcResponse;
import com.naqqa.sso.session.Session;
import com.naqqa.sso.session.SessionService;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@RestController
@ConditionalOnProperty(prefix = "naqqa.sso", name = {"enabled", "oidc-provider.enabled"}, havingValue = "true")
public class OidcController {

    public static final String INTERACTION_KIND = "oidc-interaction";
    public static final String BINDING_COOKIE = "sso_oidc_binding";
    static final int BINDING_COOKIE_SECONDS = 24 * 3600;
    static final String PAGE_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    static final List<String> FORWARDED_HEADERS = List.of("authorization", "dpop", "content-type");

    public record DeviceDecision(String userCode, Boolean approve) {
    }

    private final OidcProvider provider;
    private final SessionService sessionService;
    private final SsoSessionManager sessions;
    private final AssuranceEngine assurance;
    private final RefreshTokenManager refreshTokens;
    private final SsoTicketStore tickets;
    private final BackchannelLogoutDispatcher backchannel;
    private final SsoProperties properties;
    private final String loginUrl;
    private final String consentUrl;
    private final String logoutUrl;
    private final String deviceUrl;
    private final Duration pendingTtl;

    public OidcController(OidcProvider provider, SessionService sessionService, SsoSessionManager sessions, AssuranceEngine assurance,
                          RefreshTokenManager refreshTokens, SsoTicketStore tickets, BackchannelLogoutDispatcher backchannel,
                          SsoProperties properties, @Value("${frontend.url:}") String frontendUrl) {
        this.provider = provider;
        this.sessionService = sessionService;
        this.sessions = sessions;
        this.assurance = assurance;
        this.refreshTokens = refreshTokens;
        this.tickets = tickets;
        this.backchannel = backchannel;
        this.properties = properties;
        SsoProperties.OidcProvider p = properties.getOidcProvider();
        String front = OidcProviderConfiguration.trimSlash(frontendUrl == null ? "" : frontendUrl);
        this.loginUrl = pick(p.getLoginUrl(), front + "/login");
        this.consentUrl = pick(p.getConsentUrl(), front + "/consent");
        this.logoutUrl = pick(p.getLogoutUrl(), front + "/logout");
        this.deviceUrl = pick(p.getDeviceUrl(), front + "/device");
        this.pendingTtl = p.getPendingTtl() == null ? Duration.ofMinutes(10) : p.getPendingTtl();
    }

    private static String pick(String configured, String fallback) {
        return configured != null && !configured.isBlank() ? configured : fallback;
    }

    static OidcRequest oidcRequest(HttpServletRequest request) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, Arrays.asList(v)));
        Map<String, String> headers = new LinkedHashMap<>();
        for (String h : FORWARDED_HEADERS) {
            String v = request.getHeader(h);
            if (v != null) {
                headers.put(h, v);
            }
        }
        return new OidcRequest(request.getMethod(), params, headers, SsoWeb.cookie(request, SsoSessionManager.SESSION_COOKIE),
                SsoWeb.cookie(request, SsoLoginFlow.DEVICE_COOKIE), SsoWeb.clientIp(request), SsoSessionManager.TENANT);
    }

    static String hash(String value) {
        return Bytes.b64url(Hashes.sha256(Bytes.utf8(value)));
    }

    static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    @GetMapping("/.well-known/openid-configuration")
    public ResponseEntity<String> discovery(HttpServletRequest request, HttpServletResponse response) {
        return render(provider.discovery(), request, response, false);
    }

    @GetMapping("/oauth2/jwks")
    public ResponseEntity<String> jwks(HttpServletRequest request, HttpServletResponse response) {
        return render(provider.jwks(), request, response, false);
    }

    @RequestMapping(value = "/oauth2/authorize", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> authorize(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<String> refused = enrollmentRefusal(request);
        if (refused != null) {
            return refused;
        }
        return render(provider.authorize(oidcRequest(request)), request, response, false);
    }

    @GetMapping("/oauth2/authorize/resume")
    public ResponseEntity<String> resume(@RequestParam(name = "handle", required = false) String handle, HttpServletRequest request,
                                         HttpServletResponse response) {
        if (!boundToBrowser(handle, request)) {
            return errorPage(400, OidcError.INVALID_REQUEST);
        }
        ResponseEntity<String> refused = enrollmentRefusal(request);
        if (refused != null) {
            return refused;
        }
        return render(provider.resumeAuthorization(handle, oidcRequest(request)), request, response, false);
    }

    @PostMapping("/oauth2/authorize/consent")
    public ResponseEntity<String> consent(@RequestParam(name = "handle", required = false) String handle,
                                          @RequestParam(name = "decision", required = false) String decision,
                                          HttpServletRequest request, HttpServletResponse response) {
        if (!boundToBrowser(handle, request)) {
            return errorPage(400, OidcError.INVALID_REQUEST);
        }
        ResponseEntity<String> refused = enrollmentRefusal(request);
        if (refused != null) {
            return refused;
        }
        return render(provider.completeConsent(handle, oidcRequest(request), "approve".equals(decision)), request, response, false);
    }

    @GetMapping("/oauth2/interaction")
    public ResponseEntity<Map<String, Object>> interaction(@RequestParam(name = "handle", required = false) String handle,
                                                           HttpServletRequest request) {
        SsoTicketStore.Ticket t = boundTicket(handle, request);
        if (t == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("code", "OIDC_INTERACTION_INVALID",
                    "message", "The sign-in request expired"));
        }
        Map<String, String> d = t.data();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", d.get("type"));
        out.put("clientId", d.get("clientId"));
        out.put("clientName", d.get("clientName"));
        String scopes = d.getOrDefault("scopes", "");
        out.put("scopes", scopes.isEmpty() ? List.of() : List.of(scopes.split(" ")));
        out.put("loginHint", d.get("loginHint"));
        out.put("requiredAcr", d.get("requiredAcr"));
        out.put("forceLogin", Boolean.parseBoolean(d.get("forceLogin")));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(out);
    }

    @PostMapping("/oauth2/par")
    public ResponseEntity<String> par(HttpServletRequest request, HttpServletResponse response) {
        return render(provider.pushedAuthorization(oidcRequest(request)), request, response, false);
    }

    @PostMapping("/oauth2/token")
    public ResponseEntity<String> token(HttpServletRequest request, HttpServletResponse response) {
        String grant = request.getParameter("grant_type");
        if ((GrantTypes.CLIENT_CREDENTIALS.equals(grant) && !properties.getClientCredentials().isEnabled())
                || (GrantTypes.DEVICE_CODE.equals(grant) && !properties.getDeviceCode().isEnabled())) {
            return render(OidcResponse.error(OidcError.of("oidc.grant_disabled", OidcError.UNSUPPORTED_GRANT_TYPE,
                    "grant type is not enabled")), request, response, false);
        }
        return render(provider.token(oidcRequest(request)), request, response, false);
    }

    @RequestMapping(value = "/oauth2/userinfo", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> userinfo(HttpServletRequest request, HttpServletResponse response) {
        return render(provider.userinfo(oidcRequest(request)), request, response, false);
    }

    @PostMapping("/oauth2/introspect")
    public ResponseEntity<String> introspect(HttpServletRequest request, HttpServletResponse response) {
        return render(provider.introspect(oidcRequest(request)), request, response, false);
    }

    @PostMapping("/oauth2/revoke")
    public ResponseEntity<String> revoke(HttpServletRequest request, HttpServletResponse response) {
        return render(provider.revoke(oidcRequest(request)), request, response, false);
    }

    @PostMapping("/oauth2/device_authorization")
    public ResponseEntity<String> deviceAuthorization(HttpServletRequest request, HttpServletResponse response) {
        if (!properties.getDeviceCode().isEnabled()) {
            return render(OidcResponse.error(OidcError.of("oidc.grant_disabled", OidcError.UNAUTHORIZED_CLIENT,
                    "device flow is not enabled")), request, response, false);
        }
        return render(provider.deviceAuthorization(oidcRequest(request)), request, response, false);
    }

    @GetMapping("/oauth2/device")
    public ResponseEntity<String> device(@RequestParam(name = "user_code", required = false) String userCode) {
        if (!properties.getDeviceCode().isEnabled()) {
            return errorPage(404, OidcError.INVALID_REQUEST);
        }
        String location = deviceUrl;
        if (userCode != null && userCode.length() <= 32 && userCode.matches("[A-Za-z0-9-]+")) {
            location += (location.contains("?") ? "&" : "?") + "user_code=" + encode(userCode);
        }
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @GetMapping("/oauth2/device/lookup")
    public ResponseEntity<String> deviceLookup(@RequestParam(name = "user_code", required = false) String userCode,
                                               HttpServletRequest request, HttpServletResponse response) {
        if (!properties.getDeviceCode().isEnabled() || userCode == null) {
            return render(OidcResponse.error(OidcError.invalidRequest("oidc.user_code_invalid", "user code is invalid or expired")),
                    request, response, false);
        }
        ResponseEntity<String> refused = enrollmentRefusal(request);
        if (refused != null) {
            return refused;
        }
        return render(provider.deviceLookup(userCode, oidcRequest(request)), request, response, false);
    }

    @PostMapping(value = "/oauth2/device/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deviceDecision(@RequestBody DeviceDecision body, HttpServletRequest request, HttpServletResponse response) {
        if (!properties.getDeviceCode().isEnabled() || body == null || body.userCode() == null) {
            return render(OidcResponse.error(OidcError.invalidRequest("oidc.user_code_invalid", "user code is invalid or expired")),
                    request, response, false);
        }
        ResponseEntity<String> refused = enrollmentRefusal(request);
        if (refused != null) {
            return refused;
        }
        return render(provider.deviceDecision(body.userCode(), oidcRequest(request), Boolean.TRUE.equals(body.approve())),
                request, response, false);
    }

    @RequestMapping(value = "/oauth2/logout", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> logout(HttpServletRequest request, HttpServletResponse response) {
        OidcRequest req = oidcRequest(request);
        Session before = currentSession(req);
        OidcResponse r = provider.endSession(req);
        afterLogout(before, req, r, response);
        return render(r, request, response, true);
    }

    @PostMapping("/oauth2/logout/confirm")
    public ResponseEntity<String> confirmLogout(@RequestParam(name = "handle", required = false) String handle,
                                                @RequestParam(name = "confirm", required = false) String confirm,
                                                HttpServletRequest request, HttpServletResponse response) {
        if (!boundToBrowser(handle, request)) {
            return errorPage(400, OidcError.INVALID_REQUEST);
        }
        OidcRequest req = oidcRequest(request);
        Session before = currentSession(req);
        OidcResponse r = provider.confirmLogout(handle, req, "true".equals(confirm) || "yes".equals(confirm));
        tickets.remove(INTERACTION_KIND, hash(handle));
        afterLogout(before, req, r, response);
        return render(r, request, response, true);
    }

    @PostMapping("/oauth2/register")
    public ResponseEntity<String> register(@RequestBody(required = false) byte[] body, HttpServletRequest request,
                                           HttpServletResponse response) {
        if (!properties.getOidcProvider().isDynamicRegistration()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                    .body(Json.write(Map.of("error", OidcError.INVALID_REQUEST, "error_description", "registration is disabled")));
        }
        String json = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        return render(provider.register(oidcRequest(request), json), request, response, false);
    }

    private ResponseEntity<String> enrollmentRefusal(HttpServletRequest request) {
        String token = SsoWeb.cookie(request, SsoSessionManager.SESSION_COOKIE);
        if (token == null || token.isEmpty() || !assurance.sessionRestricted(SessionService.idHash(token))) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(SsoErrorBody.json(AccessTokenAuthenticationFilter.ENROLLMENT_REQUIRED, AccessTokenAuthenticationFilter.ENROLLMENT_MESSAGE));
    }

    private Session currentSession(OidcRequest req) {
        if (req.sessionToken() == null) {
            return null;
        }
        try {
            return sessionService.validate(req.sessionToken(), req.deviceId());
        } catch (SsoException e) {
            return null;
        }
    }

    private void afterLogout(Session before, OidcRequest req, OidcResponse r, HttpServletResponse response) {
        if (before == null || r.kind() == OidcResponse.Kind.INTERACTION || r.kind() == OidcResponse.Kind.ERROR_PAGE) {
            return;
        }
        if (currentSession(req) == null) {
            refreshTokens.revokeSession(before.idHash());
            sessions.clearCookies(response);
            CookieUtils.clearCookie(response, CookieUtils.ACCESS_TOKEN_COOKIE);
            CookieUtils.clearCookie(response, CookieUtils.REFRESH_TOKEN_COOKIE);
        }
    }

    private String binding(HttpServletRequest request, HttpServletResponse response) {
        String value = SsoWeb.cookie(request, BINDING_COOKIE);
        if (value == null || value.length() != 43) {
            value = Bytes.randomToken(32);
            CookieUtils.setClientCookie(response, BINDING_COOKIE, value, BINDING_COOKIE_SECONDS, true);
        }
        return value;
    }

    private SsoTicketStore.Ticket boundTicket(String handle, HttpServletRequest request) {
        if (handle == null || handle.length() != 43) {
            return null;
        }
        SsoTicketStore.Ticket t = tickets.peek(INTERACTION_KIND, hash(handle)).orElse(null);
        String cookie = SsoWeb.cookie(request, BINDING_COOKIE);
        if (t == null || cookie == null || t.data().get("browser") == null
                || !Bytes.constantTimeEquals(t.data().get("browser"), hash(cookie))) {
            return null;
        }
        return t;
    }

    private boolean boundToBrowser(String handle, HttpServletRequest request) {
        return boundTicket(handle, request) != null;
    }

    private ResponseEntity<String> interaction(Interaction i, HttpServletRequest request, HttpServletResponse response) {
        String browser = binding(request, response);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("browser", hash(browser));
        data.put("type", i.type().name().toLowerCase(java.util.Locale.ROOT));
        put(data, "clientId", i.clientId());
        put(data, "clientName", i.clientName());
        data.put("scopes", String.join(" ", new TreeSet<>(i.scopes())));
        put(data, "loginHint", i.loginHint());
        put(data, "requiredAcr", i.requiredAcr());
        data.put("forceLogin", Boolean.toString(i.forceLogin()));
        tickets.put(INTERACTION_KIND, hash(i.handle()), null, data, pendingTtl);
        StringBuilder q = new StringBuilder("oidc=").append(encode(i.handle()));
        String base;
        switch (i.type()) {
            case CONSENT -> base = consentUrl;
            case LOGOUT_CONFIRM -> base = logoutUrl;
            case STEP_UP -> {
                base = loginUrl;
                q.append("&stepUp=true");
                if (i.requiredAcr() != null) {
                    q.append("&acr=").append(encode(i.requiredAcr()));
                }
            }
            default -> {
                base = loginUrl;
                if (i.forceLogin()) {
                    q.append("&prompt=login");
                }
                if (i.type() == Interaction.Type.SELECT_ACCOUNT) {
                    q.append("&selectAccount=true");
                }
                if (i.requiredAcr() != null) {
                    q.append("&acr=").append(encode(i.requiredAcr()));
                }
            }
        }
        String location = base + (base.contains("?") ? "&" : "?") + q;
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("Pragma", "no-cache").build();
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    ResponseEntity<String> render(OidcResponse r, HttpServletRequest request, HttpServletResponse response, boolean logoutPage) {
        backchannel.dispatch(r.backchannelLogouts());
        if (r.kind() == OidcResponse.Kind.INTERACTION) {
            return interaction(r.interaction(), request, response);
        }
        if (r.kind() == OidcResponse.Kind.ERROR_PAGE) {
            return errorPage(r.status(), r.error());
        }
        String handle = request.getParameter("handle");
        if (handle != null && handle.length() == 43) {
            tickets.remove(INTERACTION_KIND, hash(handle));
        }
        ResponseEntity.BodyBuilder b = ResponseEntity.status(r.status());
        r.headers().forEach((k, v) -> {
            if (!"content-type".equalsIgnoreCase(k)) {
                b.header(k, v);
            }
        });
        return switch (r.kind()) {
            case REDIRECT -> b.header(HttpHeaders.LOCATION, r.redirectUrl()).build();
            case JSON -> b.contentType(MediaType.APPLICATION_JSON).body(r.bodyJson());
            default -> logoutPage ? page(r.status(), "Signed out", "You have been signed out.", null) : b.build();
        };
    }

    static ResponseEntity<String> errorPage(int status, String error) {
        String code = error == null ? OidcError.INVALID_REQUEST : error.replaceAll("[^a-z_]", "");
        return page(status, "Sign-in error", "The request could not be completed. Return to the application and try again.", code);
    }

    static ResponseEntity<String> page(int status, String title, String message, String code) {
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>").append(title)
                .append("</title></head><body><h1>").append(title).append("</h1><p>").append(message).append("</p>");
        if (code != null && !code.isEmpty()) {
            html.append("<p><code>").append(code).append("</code></p>");
        }
        html.append("</body></html>");
        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .header("Content-Security-Policy", PAGE_CSP)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(html.toString());
    }
}
