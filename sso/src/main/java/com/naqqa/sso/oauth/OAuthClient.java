package com.naqqa.sso.oauth;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.jose.JwtClaims;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.json.JsonLimits;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OAuthClient {
    public static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final int MAX_PARAM = 4096;
    private static final int MAX_REDIRECT_AFTER = 512;
    private static final JsonLimits TOKEN_LIMITS = new JsonLimits(6, 64 * 1024);
    private static final Set<String> PROVIDER_ERRORS = Set.of("access_denied", "invalid_request", "unauthorized_client",
            "unsupported_response_type", "invalid_scope", "server_error", "temporarily_unavailable", "interaction_required",
            "login_required", "consent_required", "account_selection_required", "user_cancelled_authorize");

    public record Begin(String authorizationUrl, String binding, Instant expiresAt) {
        @Override
        public String toString() {
            return "Begin[expiresAt=" + expiresAt + "]";
        }
    }

    public record Completion(ExternalIdentity identity, String redirectAfter) {
    }

    private record Endpoints(String authorization, String token, String jwks, ClientAuth clientAuth, boolean issRequired) {
    }

    private final ProviderRegistry registry;
    private final StateStore states;
    private final SafeHttp http;
    private final Clock clock;
    private final Duration stateTtl;
    private final JwksCache jwks;
    private final DiscoveryClient discovery;

    public OAuthClient(ProviderRegistry registry, StateStore states, SafeHttp http, Clock clock) {
        this(registry, states, http, clock, STATE_TTL);
    }

    public OAuthClient(ProviderRegistry registry, StateStore states, SafeHttp http, Clock clock, Duration stateTtl) {
        if (registry == null || states == null || http == null || clock == null || stateTtl == null
                || stateTtl.isNegative() || stateTtl.isZero()) {
            throw new SsoException("oauth.client_invalid");
        }
        this.registry = registry;
        this.states = states;
        this.http = http;
        this.clock = clock;
        this.stateTtl = stateTtl;
        this.jwks = new JwksCache(http, clock);
        this.discovery = new DiscoveryClient(http, clock);
    }

    public Begin begin(String providerId, String redirectAfter) {
        ProviderConfig config = provider(providerId);
        String target = checkRedirectAfter(redirectAfter);
        Endpoints ep = endpoints(config);
        http.checkUrl(ep.authorization());
        String state = Bytes.randomToken(32);
        String nonce = Bytes.randomToken(32);
        String verifier = config.pkce() ? Bytes.randomToken(32) : null;
        String binding = Bytes.randomToken(32);
        Instant expires = clock.instant().plus(stateTtl);
        states.put(new StateRecord(hash(state), config.id(), nonce, verifier, hash(binding), target, expires));
        Map<String, String> q = new LinkedHashMap<>();
        q.put("response_type", "code");
        q.put("client_id", config.clientId());
        q.put("redirect_uri", config.redirectUri());
        if (!config.scopes().isEmpty()) {
            q.put("scope", String.join(" ", config.scopes()));
        }
        q.put("state", state);
        if (config.kind().oidc()) {
            q.put("nonce", nonce);
        }
        if (verifier != null) {
            q.put("code_challenge", Bytes.b64url(Hashes.sha256(Bytes.utf8(verifier))));
            q.put("code_challenge_method", "S256");
        }
        if (config.responseMode().equals("form_post")) {
            q.put("response_mode", "form_post");
        }
        for (Map.Entry<String, String> e : config.extraAuthParams().entrySet()) {
            q.putIfAbsent(e.getKey(), e.getValue());
        }
        String sep = ep.authorization().contains("?") ? "&" : "?";
        return new Begin(ep.authorization() + sep + SafeHttp.formEncode(q), binding, expires);
    }

    public ExternalIdentity complete(CallbackParams params, String binding) {
        return completeWithRedirect(null, params, binding).identity();
    }

    public ExternalIdentity complete(String providerId, CallbackParams params, String binding) {
        return completeWithRedirect(providerId, params, binding).identity();
    }

    public Completion completeWithRedirect(String expectedProviderId, CallbackParams params, String binding) {
        String state = params.get("state");
        if (state == null || state.isEmpty() || state.length() > 256) {
            throw new SsoException("oauth.state_missing");
        }
        StateRecord rec = states.consume(hash(state)).orElseThrow(() -> new SsoException("oauth.state_invalid"));
        if (!clock.instant().isBefore(rec.expiresAt())) {
            throw new SsoException("oauth.state_expired");
        }
        if (binding == null || binding.isEmpty() || binding.length() > 256) {
            throw new SsoException("oauth.binding_missing");
        }
        if (!Bytes.constantTimeEquals(hash(binding), rec.bindingHash())) {
            throw new SsoException("oauth.binding_mismatch");
        }
        if (expectedProviderId != null && !expectedProviderId.equals(rec.providerId())) {
            throw new SsoException("oauth.provider_mismatch");
        }
        ProviderConfig config = provider(rec.providerId());
        String iss = params.get("iss");
        if (expectedProviderId == null && (!config.pkce() || (!registry.singleProvider() && iss == null))) {
            throw new SsoException("oauth.provider_required");
        }
        if (iss != null && !issuerMatches(config, iss)) {
            throw new SsoException("oauth.issuer_mismatch");
        }
        String error = params.get("error");
        if (error != null) {
            throw new SsoException(PROVIDER_ERRORS.contains(error) ? "oauth.provider_error." + error : "oauth.provider_error");
        }
        Endpoints ep = endpoints(config);
        if (iss == null && ep.issRequired()) {
            throw new SsoException("oauth.issuer_missing");
        }
        String code = params.get("code");
        if (code == null || code.isEmpty()) {
            throw new SsoException("oauth.code_missing");
        }
        if (code.length() > MAX_PARAM) {
            throw new SsoException("oauth.code_invalid");
        }
        Map<String, Object> token = exchange(config, ep, code, rec.codeVerifier());
        String accessToken = stringMember(token, "access_token");
        Object type = token.get("token_type");
        if (type != null && !(type instanceof String && ((String) type).equalsIgnoreCase("bearer"))) {
            throw new SsoException("oauth.token_type_invalid");
        }
        ExternalIdentity identity;
        switch (config.kind()) {
            case GITHUB:
                identity = GithubProfile.fetch(http, config, accessToken);
                break;
            case FACEBOOK:
                identity = FacebookProfile.fetch(http, config, accessToken, clock);
                break;
            default:
                String idToken = token.get("id_token") instanceof String ? (String) token.get("id_token") : null;
                if (idToken == null || idToken.isEmpty()) {
                    throw new SsoException("oauth.id_token_missing");
                }
                JwtClaims claims = IdTokenVerifier.verify(config, ep.jwks(), idToken, rec.nonce(), accessToken, jwks, clock);
                identity = OidcIdentityMapper.map(config, claims, params.get("user"));
        }
        return new Completion(identity, rec.redirectAfter());
    }

    private Map<String, Object> exchange(ProviderConfig config, Endpoints ep, String code, String verifier) {
        Map<String, String> form = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", config.redirectUri());
        if (verifier != null) {
            form.put("code_verifier", verifier);
        }
        String secret = config.clientSecret() == null ? null : config.clientSecret().secret();
        if (secret != null && ep.clientAuth() == ClientAuth.BASIC) {
            String cred = SafeHttp.encode(config.clientId()) + ":" + SafeHttp.encode(secret);
            headers.put("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(Bytes.utf8(cred)));
        } else {
            form.put("client_id", config.clientId());
            if (secret != null) {
                form.put("client_secret", secret);
            }
        }
        SafeHttp.Response r = http.postForm(ep.token(), form, headers, List.of("application/json", "text/javascript"));
        if (r.status() != 200) {
            throw new SsoException("oauth.token_exchange_failed");
        }
        try {
            return Json.parseObject(r.body(), TOKEN_LIMITS);
        } catch (SsoException e) {
            throw new SsoException("oauth.token_response_invalid");
        }
    }

    private static String stringMember(Map<String, Object> m, String name) {
        Object v = m.get(name);
        if (!(v instanceof String) || ((String) v).isEmpty() || ((String) v).length() > 16384) {
            throw new SsoException("oauth.token_response_invalid");
        }
        return (String) v;
    }

    private Endpoints endpoints(ProviderConfig config) {
        if (config.discovery()) {
            DiscoveryClient.Document d = discovery.discover(config.issuer());
            return new Endpoints(d.authorizationEndpoint(), d.tokenEndpoint(), d.jwksUri(), d.clientAuth(),
                    d.issParameterSupported());
        }
        return new Endpoints(config.authorizationEndpoint(), config.tokenEndpoint(), config.jwksUri(), config.clientAuth(), false);
    }

    private static boolean issuerMatches(ProviderConfig config, String iss) {
        if (iss.isEmpty() || iss.length() > MAX_PARAM) {
            return false;
        }
        if (config.issuer() != null) {
            return config.issuer().equals(iss) || config.issuerAliases().contains(iss);
        }
        String template = config.issuerTemplate();
        if (template == null) {
            return false;
        }
        int at = template.indexOf(ProviderConfig.TENANT_PLACEHOLDER);
        String prefix = template.substring(0, at);
        String suffix = template.substring(at + ProviderConfig.TENANT_PLACEHOLDER.length());
        if (!iss.startsWith(prefix) || !iss.endsWith(suffix) || iss.length() <= prefix.length() + suffix.length()) {
            return false;
        }
        String tid = iss.substring(prefix.length(), iss.length() - suffix.length());
        if (!tid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            return false;
        }
        return config.allowedTenants().isEmpty() || config.allowedTenants().contains(tid);
    }

    private ProviderConfig provider(String id) {
        if (id == null) {
            throw new SsoException("oauth.provider_unknown");
        }
        return registry.find(id).orElseThrow(() -> new SsoException("oauth.provider_unknown"));
    }

    static String checkRedirectAfter(String value) {
        if (value == null || value.isEmpty()) {
            return "/";
        }
        if (value.length() > MAX_REDIRECT_AFTER || value.charAt(0) != '/') {
            throw new SsoException("oauth.redirect_after_invalid");
        }
        if (value.length() > 1 && (value.charAt(1) == '/' || value.charAt(1) == '\\')) {
            throw new SsoException("oauth.redirect_after_invalid");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c == '\\' || c == 0x7f) {
                throw new SsoException("oauth.redirect_after_invalid");
            }
        }
        return value;
    }

    static String hash(String value) {
        return Bytes.b64url(Hashes.sha256(Bytes.utf8(value)));
    }
}
