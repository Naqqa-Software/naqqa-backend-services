package com.naqqa.auth.sso.social;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.security.CookieUtils;
import com.naqqa.auth.security.NaqqaAuthenticationToken;
import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.admin.SsoAdminAuthorities;
import com.naqqa.auth.sso.flow.AccountLinkService;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.SsoAccounts;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoClientPolicy;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.flow.SsoSecurityNotices;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.ldap.DirectoryLogin;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.oauth.CallbackParams;
import com.naqqa.sso.oauth.ExternalIdentity;
import com.naqqa.sso.oauth.LinkingPolicy;
import com.naqqa.sso.oauth.OAuthClient;
import com.naqqa.sso.oauth.ProviderConfig;
import com.naqqa.sso.oauth.ProviderKind;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@ConditionalOnProperty(prefix = "naqqa.sso", name = {"enabled", "social.enabled"}, havingValue = "true")
public class SocialLoginController {

    public static final String BINDING_COOKIE = "sso_oauth_binding";
    public static final String FLOW_KIND = "social-flow";
    static final String CALLBACK_PATH = "/login/oauth2/code/";
    static final Set<String> CALLBACK_PARAMS = Set.of("state", "code", "error", "iss", "user");

    private final OAuthClient oauth;
    private final SocialLoginConfiguration.SocialProviders providers;
    private final SsoTicketStore tickets;
    private final SsoClientPolicy clientPolicy;
    private final AccountLinkService links;
    private final SsoAccounts accounts;
    private final SsoLoginFlow loginFlow;
    private final SsoSessionManager sessions;
    private final SsoAudit audit;
    private final SsoProperties properties;
    private final AssuranceEngine assurance;
    private final SsoSecurityNotices notices;
    private final ObjectProvider<DirectoryLogin> directories;
    private final String successBase;
    private final String failureUrl;

    public SocialLoginController(OAuthClient oauth, SocialLoginConfiguration.SocialProviders providers, SsoTicketStore tickets,
                                 SsoClientPolicy clientPolicy, AccountLinkService links, SsoAccounts accounts, SsoLoginFlow loginFlow,
                                 SsoSessionManager sessions, SsoAudit audit, SsoProperties properties, AssuranceEngine assurance,
                                 SsoSecurityNotices notices, ObjectProvider<DirectoryLogin> directories,
                                 @Value("${frontend.url:}") String frontendUrl) {
        this.oauth = oauth;
        this.providers = providers;
        this.tickets = tickets;
        this.clientPolicy = clientPolicy;
        this.links = links;
        this.accounts = accounts;
        this.loginFlow = loginFlow;
        this.sessions = sessions;
        this.audit = audit;
        this.properties = properties;
        this.assurance = assurance;
        this.notices = notices;
        this.directories = directories;
        String front = frontendUrl == null ? "" : frontendUrl.replaceAll("/+$", "");
        String s = properties.getSocial().getSuccessUrl();
        this.successBase = s != null && !s.isBlank() ? s.replaceAll("/+$", "") : front;
        String f = properties.getSocial().getFailureUrl();
        this.failureUrl = f != null && !f.isBlank() ? f : front + "/login";
    }

    static String hash(String value) {
        return Bytes.b64url(Hashes.sha256(Bytes.utf8(value)));
    }

    private ProviderConfig config(String provider) {
        String p = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        ProviderConfig c = providers.configs().get(p);
        if (c == null) {
            throw SsoApiException.notFound("PROVIDER_UNKNOWN", "Unknown sign-in provider");
        }
        return c;
    }

    @GetMapping("/oauth2/authorization/{provider}")
    public ResponseEntity<Void> begin(@PathVariable String provider,
                                      @RequestParam(name = "redirect", required = false) String redirect,
                                      @RequestParam(name = "link", required = false) String linkIntent,
                                      HttpServletRequest request, HttpServletResponse response) {
        ProviderConfig config = config(provider);
        SsoClientPolicy.Policy policy = clientPolicy.forRequest(request);
        if (!policy.social().contains(config.id())) {
            throw SsoApiException.disabled(config.id());
        }
        Map<String, String> flow = new LinkedHashMap<>();
        flow.put("provider", config.id());
        if (policy.clientId() != null) {
            flow.put("clientId", policy.clientId());
        }
        String userId = null;
        if (linkIntent != null) {
            NaqqaAuthenticationToken auth = SsoWeb.authentication();
            if (auth == null || auth.getPrincipal().isImpersonated() || auth.getPrincipal().getSessionId() == null) {
                throw SsoApiException.unauthorized("UNAUTHORIZED", "Authentication required");
            }
            AuthPrincipal p = auth.getPrincipal();
            userId = String.valueOf(p.getUserId());
            flow.put("link", linkIntent);
            flow.put("sid", p.getSessionId());
        }
        OAuthClient.Begin begin;
        try {
            begin = oauth.begin(config.id(), redirect);
        } catch (SsoException e) {
            throw SsoApiException.badRequest("SOCIAL_REQUEST_INVALID", "The sign-in request is invalid");
        }
        tickets.put(FLOW_KIND, hash(begin.binding()), userId, flow, OAuthClient.STATE_TTL);
        bindingCookie(response, config, begin.binding(), (int) OAuthClient.STATE_TTL.getSeconds());
        audit.record("social.start", userId, config.id(), "pending", Map.of("provider", config.id()));
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, begin.authorizationUrl())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    static boolean formPost(ProviderConfig config) {
        return "form_post".equals(config.responseMode());
    }

    static void bindingCookie(HttpServletResponse response, ProviderConfig config, String value, int maxAge) {
        String path = CALLBACK_PATH + config.id();
        if (formPost(config)) {
            response.addHeader("Set-Cookie", String.format("%s=%s; Max-Age=%d; HttpOnly; Secure; SameSite=None; Path=%s",
                    BINDING_COOKIE, value, maxAge, path));
        } else {
            response.addHeader("Set-Cookie", String.format("%s=%s; Max-Age=%d; HttpOnly%s; SameSite=Lax; Path=%s",
                    BINDING_COOKIE, value, maxAge, CookieUtils.isSecure() ? "; Secure" : "", path));
        }
    }

    @GetMapping(CALLBACK_PATH + "{provider}")
    public ResponseEntity<Void> callback(@PathVariable String provider, HttpServletRequest request, HttpServletResponse response) {
        ProviderConfig config = config(provider);
        if (formPost(config)) {
            return redirect(failure("SOCIAL_FAILED"));
        }
        return complete(config, request, response);
    }

    @PostMapping(CALLBACK_PATH + "{provider}")
    public ResponseEntity<Void> callbackPost(@PathVariable String provider, HttpServletRequest request, HttpServletResponse response) {
        ProviderConfig config = config(provider);
        if (!formPost(config)) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        }
        return complete(config, request, response);
    }

    private ResponseEntity<Void> complete(ProviderConfig config, HttpServletRequest request, HttpServletResponse response) {
        String binding = SsoWeb.cookie(request, BINDING_COOKIE);
        bindingCookie(response, config, "", 0);
        Map<String, String> params = new LinkedHashMap<>();
        for (String name : CALLBACK_PARAMS) {
            String[] v = request.getParameterValues(name);
            if (v != null && v.length == 1) {
                params.put(name, v[0]);
            } else if (v != null && v.length > 1) {
                return redirect(failure("SOCIAL_FAILED"));
            }
        }
        SsoTicketStore.Ticket flow = binding == null ? null : tickets.take(FLOW_KIND, hash(binding)).orElse(null);
        OAuthClient.Completion completion;
        try {
            completion = oauth.completeWithRedirect(config.id(), CallbackParams.of(params), binding);
        } catch (SsoException e) {
            audit.record("social.callback", null, config.id(), "failure", Map.of("reason", e.code()));
            return redirect(failure(e.code().startsWith("oauth.provider_error.access_denied") ? "SOCIAL_CANCELLED" : "SOCIAL_FAILED"));
        }
        if (flow == null || !config.id().equals(flow.data().get("provider"))) {
            audit.record("social.callback", null, config.id(), "failure", Map.of("reason", "flow_missing"));
            return redirect(failure("SOCIAL_FAILED"));
        }
        ExternalIdentity identity = completion.identity();
        try {
            String target = flow.data().containsKey("link")
                    ? link(config, identity, flow, completion.redirectAfter())
                    : login(config, identity, flow, completion.redirectAfter(), request, response);
            return redirect(target);
        } catch (SsoApiException e) {
            return redirect(failure(e.getCode()));
        }
    }

    private String link(ProviderConfig config, ExternalIdentity identity, SsoTicketStore.Ticket flow, String redirectAfter) {
        String userId = flow.userId();
        String sid = flow.data().get("sid");
        NaqqaAuthenticationToken auth = SsoWeb.authentication();
        if (auth != null && (!String.valueOf(auth.getPrincipal().getUserId()).equals(userId)
                || !sid.equals(auth.getPrincipal().getSessionId()))) {
            throw SsoApiException.forbidden("LINK_SESSION_MISMATCH", "Linking must finish in the session that started it");
        }
        if (sessions.find(sid).isEmpty()) {
            throw SsoApiException.unauthorized("SESSION_REQUIRED", "Sign in again");
        }
        if (unverifiedEmailProvider(config)) {
            links.completeOwned(flow.data().get("link"), userId, sid, identity(identity));
        } else {
            links.complete(flow.data().get("link"), userId, sid, identity(identity));
        }
        notices.send(accounts.byId(userId).orElse(null), SsoSecurityNotices.ACCOUNT_LINKED);
        return successBase + redirectAfter + (redirectAfter.contains("?") ? "&" : "?") + "linked=" + encode(config.id());
    }

    static boolean unverifiedEmailProvider(ProviderConfig config) {
        return config.kind() == ProviderKind.FACEBOOK;
    }

    static AccountLinkService.Identity identity(ExternalIdentity identity) {
        return new AccountLinkService.Identity(identity.provider(), identity.subject(), identity.email(), identity.emailVerified());
    }

    private Optional<String> legacyLink(ProviderConfig config, String subject) {
        Optional<UserEntity> u = switch (config.id()) {
            case "google" -> accounts.byGoogleId(subject);
            case "facebook" -> accounts.byFacebookId(subject);
            case "apple" -> accounts.byAppleId(subject);
            default -> Optional.empty();
        };
        return u.map(x -> String.valueOf(x.getId()));
    }

    private boolean directoryManaged(UserEntity user) {
        DirectoryLogin directory = directories.getIfAvailable();
        return directory != null && directory.isDirectoryManaged(String.valueOf(user.getId()));
    }

    private String login(ProviderConfig config, ExternalIdentity identity, SsoTicketStore.Ticket flow, String redirectAfter,
                         HttpServletRequest request, HttpServletResponse response) {
        SsoProperties.Social social = properties.getSocial();
        Optional<String> linked = links.findUser(config.id(), identity.subject());
        if (linked.isEmpty()) {
            linked = legacyLink(config, identity.subject());
        }
        Optional<UserEntity> byEmail = accounts.byEmail(identity.email());
        boolean autoLink = social.isAutoLink() && byEmail.map(u -> u.isEmailVerified() && AssuranceEngine.mayAutoLink(assurance.enrolled(u))
                && !SsoAdminAuthorities.administrative(TokenService.authoritiesOf(u)) && !directoryManaged(u)).orElse(true);
        LinkingPolicy.Decision decision = new LinkingPolicy(autoLink).decide(identity, config, linked,
                byEmail.map(u -> String.valueOf(u.getId())), null);
        UserEntity user;
        switch (decision.outcome()) {
            case LOGIN_EXISTING_LINK -> {
                user = accounts.byId(decision.userId()).orElse(null);
                if (user != null && user.isEmailVerified()
                        && AccountLinkService.ORIGIN_UNVERIFIED.equals(links.origin(config.id(), identity.subject()).orElse(null))) {
                    audit.record("social.login", decision.userId(), config.id(), "denied", Map.of("reason", "email_verified_elsewhere"));
                    throw SsoApiException.forbidden("LINK_REQUIRES_LOGIN", "Sign in with your existing method, then link this provider");
                }
            }
            case LINK_TO_EXISTING_ACCOUNT -> {
                user = accounts.byId(decision.userId()).orElse(null);
                if (user != null && user.isEnabled() && !user.isBlocked()) {
                    String intent = links.startIntent(decision.userId(), null, config.id());
                    links.complete(intent, decision.userId(), null, identity(identity));
                    notices.send(user, SsoSecurityNotices.ACCOUNT_LINKED);
                }
            }
            case CREATE_ACCOUNT -> {
                if (!social.isCreateAccounts()) {
                    audit.record("social.login", null, config.id(), "denied", Map.of("reason", "signup_disabled"));
                    throw SsoApiException.forbidden("SIGNUP_DISABLED", "Sign-up is disabled");
                }
                boolean unverifiedSignup = unverifiedEmailProvider(config);
                if (identity.email() == null || (!decision.emailTrusted() && !unverifiedSignup)) {
                    audit.record("social.login", null, config.id(), "denied", Map.of("reason", "email_unverified"));
                    throw SsoApiException.forbidden("EMAIL_NOT_VERIFIED", "The provider did not verify the email address");
                }
                user = accounts.create(identity.email(), identity.name(), List.of(), true, decision.emailTrusted());
                if (decision.emailTrusted()) {
                    String intent = links.startIntent(String.valueOf(user.getId()), null, config.id());
                    links.complete(intent, String.valueOf(user.getId()), null, identity(identity));
                } else {
                    links.bind(String.valueOf(user.getId()), identity(identity), AccountLinkService.ORIGIN_UNVERIFIED);
                }
                audit.record("social.signup", String.valueOf(user.getId()), String.valueOf(user.getId()), "success",
                        Map.of("provider", config.id(), "emailVerified", Boolean.toString(decision.emailTrusted())));
            }
            default -> {
                audit.record("social.login", decision.userId(), config.id(), "denied", Map.of("reason",
                        identity.emailVerified() || unverifiedEmailProvider(config) ? "link_requires_login" : "email_unverified"));
                throw SsoApiException.forbidden(identity.emailVerified() || unverifiedEmailProvider(config) ? "LINK_REQUIRES_LOGIN" : "EMAIL_NOT_VERIFIED",
                        "Sign in with your existing method, then link this provider");
            }
        }
        if (user == null || !user.isEnabled() || user.isBlocked()) {
            audit.record("social.login", decision.userId(), config.id(), "denied", Map.of("reason", "account_unavailable"));
            throw SsoApiException.forbidden("ACCOUNT_BLOCKED", "Sign-in is not possible for this account");
        }
        String clientId = flow.data().get("clientId");
        SsoLoginFlow.Context base = loginFlow.context(request, response, null, "web", false);
        SsoLoginFlow.Context ctx = new SsoLoginFlow.Context(clientPolicy.forClient(clientId), null, "web", false, base.deviceToken(),
                base.ip(), base.userAgent(), base.rememberToken());
        Map<String, Object> result = loginFlow.afterFirstFactor(user, List.of("fed"), ctx, response, Set.of());
        String userId = String.valueOf(user.getId());
        audit.record("social.login", userId, userId, "success", Map.of("provider", config.id(), "outcome", decision.code()));
        if ("mfa_required".equals(result.get("status"))) {
            Object methods = result.get("methods");
            return failureUrl + "#mfaToken=" + encode(String.valueOf(result.get("mfaToken")))
                    + "&methods=" + encode(methods instanceof List<?> l ? String.join(",", l.stream().map(String::valueOf).toList()) : "")
                    + "&redirect=" + encode(redirectAfter);
        }
        return SsoLoginFlow.withEnrollmentMarker(successBase + redirectAfter, result);
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
