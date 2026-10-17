package com.naqqa.auth.sso.admin;

import com.naqqa.auth.security.RequiresStepUp;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoClientPolicy;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.oidc.MongoClientStore;
import com.naqqa.auth.sso.store.MongoApiTokenStore;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.oauth.SafeHttp;
import com.naqqa.sso.oidc.ClientAuthMethod;
import com.naqqa.sso.oidc.ClientRegistration;
import com.naqqa.sso.oidc.ClientType;
import com.naqqa.sso.oidc.GrantTypes;
import com.naqqa.sso.password.PasswordHasher;
import com.naqqa.sso.tokens.ApiTokenService;
import com.naqqa.sso.util.Bytes;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@RestController
@RequestMapping(SsoAdminAuthorities.BASE + "/tenants/{tenantId}/clients")
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
@PreAuthorize("hasAuthority('" + SsoAdminAuthorities.CLIENTS + "')")
public class ClientAdminController {

    static final String CLIENT_ID_PATTERN = "[A-Za-z0-9._-]{3,64}";
    static final String SCOPE_PATTERN = "[A-Za-z0-9._:-]{1,64}";
    static final String PROVIDER_PATTERN = "[a-z0-9_-]{1,32}";
    static final int MAX_SCOPES = 50;

    record Resolved(String clientId, String tenantId, ClientRegistration oidc, boolean managed,
                    Optional<SsoClientSettingsStore.Settings> settings) {
    }

    private final ObjectProvider<MongoClientStore> clientStore;
    private final ObjectProvider<PasswordHasher> secretHasher;
    private final SsoClientSettingsStore settingsStore;
    private final SsoProperties properties;
    private final SsoClientPolicy policy;
    private final SsoAdminScope scope;
    private final SsoAudit audit;
    private final MongoApiTokenStore tokenStore;
    private final ApiTokenService tokens;
    private final Clock clock;
    private final SafeHttp http;

    public ClientAdminController(ObjectProvider<MongoClientStore> clientStore,
                                 @Qualifier("ssoOidcSecretHasher") ObjectProvider<PasswordHasher> secretHasher,
                                 SsoClientSettingsStore settingsStore, SsoProperties properties, SsoClientPolicy policy, SsoAdminScope scope,
                                 SsoAudit audit, MongoApiTokenStore tokenStore, ApiTokenService tokens, Clock clock) {
        this.clientStore = clientStore;
        this.secretHasher = secretHasher;
        this.settingsStore = settingsStore;
        this.properties = properties;
        this.policy = policy;
        this.scope = scope;
        this.audit = audit;
        this.tokenStore = tokenStore;
        this.tokens = tokens;
        this.clock = clock;
        this.http = new SafeHttp(properties.getOidcProvider().getBackchannel().isTestMode()
                ? SafeHttp.Options.forTests() : SafeHttp.Options.production());
    }

    static long oidcClientCount(MongoClientStore store, String tenantId) {
        return store == null ? 0 : store.listManaged(tenantId).size() + store.listConfigured(tenantId).size();
    }

    private Resolved resolve(String tenantId, String clientId) {
        MongoClientStore store = clientStore.getIfAvailable();
        ClientRegistration oidc = store == null ? null : store.find(clientId).orElse(null);
        Optional<SsoClientSettingsStore.Settings> settings = settingsStore.find(clientId);
        String owner = oidc != null ? oidc.tenantId()
                : settings.isPresent() ? settings.get().tenantId()
                : properties.getClients().containsKey(clientId) ? SsoSessionManager.TENANT : null;
        if (owner == null || !owner.equals(tenantId)) {
            throw SsoApiException.notFound("NOT_FOUND", "Client not found");
        }
        return new Resolved(clientId, owner, oidc, oidc != null && !store.isConfigured(clientId), settings);
    }

    private Map<String, Object> view(Resolved r) {
        Map<String, Object> m = new LinkedHashMap<>();
        ClientRegistration c = r.oidc();
        SsoClientSettingsStore.Settings s = r.settings().orElse(null);
        SsoProperties.Client configured = properties.getClients().get(r.clientId());
        m.put("clientId", r.clientId());
        m.put("tenantId", r.tenantId());
        m.put("name", c != null ? c.clientName() : s != null && s.brandingName() != null ? s.brandingName() : r.clientId());
        m.put("type", c == null ? null : c.type() == ClientType.PUBLIC ? "public" : "confidential");
        m.put("authMethod", c == null ? null : c.authMethod().value());
        m.put("managed", r.managed());
        m.put("redirectUris", c == null ? List.of() : c.redirectUris());
        m.put("postLogoutRedirectUris", c == null ? List.of() : c.postLogoutRedirectUris());
        m.put("backchannelLogoutUri", c == null ? null : c.backchannelLogoutUri());
        m.put("grantTypes", c == null ? List.of() : new TreeSet<>(c.grantTypes()));
        m.put("scopes", c == null ? List.of() : new TreeSet<>(c.scopes()));
        m.put("firstParty", c != null && c.firstParty());
        m.put("pkceRequired", true);
        boolean enabled = !(configured != null && Boolean.FALSE.equals(configured.getEnabled())) && (s == null || !Boolean.FALSE.equals(s.enabled()));
        m.put("enabled", enabled);
        Object methods;
        try {
            methods = policy.forClient(r.clientId()).describe();
        } catch (SsoApiException e) {
            methods = null;
        }
        m.put("methods", methods);
        m.put("methodOverrides", s == null ? Map.of() : s.methods());
        m.put("socialProviders", s == null ? null : s.socialProviders());
        m.put("allowedOrigins", policy.allowedOrigins(r.clientId()));
        Map<String, Object> branding = new LinkedHashMap<>();
        branding.put("name", s == null ? null : s.brandingName());
        branding.put("logoUrl", s == null ? null : s.logoUrl());
        m.put("branding", branding);
        return m;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String tenantId) {
        SsoTenantStore.Tenant t = scope.tenant(tenantId);
        Set<String> ids = new LinkedHashSet<>();
        MongoClientStore store = clientStore.getIfAvailable();
        if (store != null) {
            store.listConfigured(t.id()).forEach(c -> ids.add(c.clientId()));
            store.listManaged(t.id()).forEach(c -> ids.add(c.clientId()));
        }
        if (SsoSessionManager.TENANT.equals(t.id())) {
            ids.addAll(new TreeSet<>(properties.getClients().keySet()));
        }
        settingsStore.listByTenant(t.id()).forEach(s -> ids.add(s.clientId()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            try {
                out.add(view(resolve(t.id(), id)));
            } catch (SsoApiException ignored) {
            }
        }
        return out;
    }

    @GetMapping("/{clientId}")
    public Map<String, Object> get(@PathVariable String tenantId, @PathVariable String clientId) {
        SsoTenantStore.Tenant t = scope.tenant(tenantId);
        return view(resolve(t.id(), clientId));
    }

    static String codeFor(SsoException e) {
        String code = e.code() == null ? "" : e.code();
        if (code.contains("redirect")) {
            return "REDIRECT_URI_INVALID";
        }
        if (code.contains("grant")) {
            return "GRANT_TYPE_INVALID";
        }
        if (code.contains("scope")) {
            return "SCOPE_INVALID";
        }
        if (code.contains("uri")) {
            return "BACKCHANNEL_URI_INVALID";
        }
        return "CLIENT_INVALID";
    }

    static String[] grantTypes(List<String> raw, String[] fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw.isEmpty() || raw.size() > GrantTypes.SUPPORTED.size()) {
            throw SsoApiException.badRequest("GRANT_TYPE_INVALID", "Unsupported grant types");
        }
        for (String g : raw) {
            if (g == null || !GrantTypes.SUPPORTED.contains(g)) {
                throw SsoApiException.badRequest("GRANT_TYPE_INVALID", "Unsupported grant type");
            }
        }
        return new LinkedHashSet<>(raw).toArray(String[]::new);
    }

    static String[] scopes(List<String> raw, String[] fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw.size() > MAX_SCOPES) {
            throw SsoApiException.badRequest("SCOPE_INVALID", "Too many scopes");
        }
        for (String s : raw) {
            if (s == null || !s.matches(SCOPE_PATTERN)) {
                throw SsoApiException.badRequest("SCOPE_INVALID", "Invalid scope");
            }
        }
        return new LinkedHashSet<>(raw).toArray(String[]::new);
    }

    private SsoClientSettingsStore.Settings mergeSettings(String clientId, String tenantId, Optional<SsoClientSettingsStore.Settings> existing,
                                                         SsoAdminRequests.ClientInput in) {
        SsoClientSettingsStore.Settings e = existing.orElse(null);
        Boolean enabled = in.enabled() != null ? in.enabled() : e == null ? null : e.enabled();
        Map<String, Boolean> methods = new LinkedHashMap<>(e == null ? Map.of() : e.methods());
        if (in.methods() != null) {
            for (Map.Entry<String, Boolean> m : in.methods().entrySet()) {
                if (!SsoClientSettingsStore.METHODS.contains(m.getKey())) {
                    throw SsoApiException.badRequest("METHOD_INVALID", "Unknown method");
                }
                if (m.getValue() == null) {
                    methods.remove(m.getKey());
                } else {
                    methods.put(m.getKey(), m.getValue());
                }
            }
        }
        List<String> social = e == null ? null : e.socialProviders();
        if (in.socialProviders() != null) {
            if (in.socialProviders().size() > 20) {
                throw SsoApiException.badRequest("SOCIAL_PROVIDER_INVALID", "Too many providers");
            }
            for (String p : in.socialProviders()) {
                if (p == null || !p.matches(PROVIDER_PATTERN)) {
                    throw SsoApiException.badRequest("SOCIAL_PROVIDER_INVALID", "Invalid provider");
                }
            }
            social = new ArrayList<>(new LinkedHashSet<>(in.socialProviders()));
        }
        List<String> origins = in.allowedOrigins() != null ? SsoAdminValidation.origins(in.allowedOrigins())
                : e == null ? List.of() : e.allowedOrigins();
        String brandingName = e == null ? null : e.brandingName();
        String logoUrl = e == null ? null : e.logoUrl();
        if (in.branding() != null) {
            brandingName = in.branding().name() == null || in.branding().name().isBlank() ? null
                    : SsoAdminValidation.text(in.branding().name(), 120, "BRANDING_INVALID");
            logoUrl = SsoAdminValidation.logoUrl(in.branding().logoUrl());
        }
        return new SsoClientSettingsStore.Settings(clientId, tenantId, enabled, methods, social, origins, brandingName, logoUrl, clock.instant());
    }

    private static boolean touchesOidc(SsoAdminRequests.ClientInput in) {
        return in.name() != null || in.type() != null || in.redirectUris() != null || in.postLogoutRedirectUris() != null
                || in.backchannelLogoutUri() != null || in.grantTypes() != null || in.scopes() != null || in.firstParty() != null;
    }

    private MongoClientStore requireStore() {
        MongoClientStore store = clientStore.getIfAvailable();
        PasswordHasher hasher = secretHasher.getIfAvailable();
        if (store == null || hasher == null) {
            throw SsoApiException.notConfigured("OIDC provider");
        }
        return store;
    }

    private ClientRegistration.Builder applyOidc(ClientRegistration.Builder b, SsoAdminRequests.ClientInput in, ClientRegistration base) {
        if (in.name() != null) {
            b.name(SsoAdminValidation.text(in.name(), 120, "NAME_INVALID"));
        }
        if (in.redirectUris() != null) {
            b.redirectUris(SsoAdminValidation.redirectUris(in.redirectUris(), "REDIRECT_URI_INVALID").toArray(String[]::new));
        }
        if (in.postLogoutRedirectUris() != null) {
            b.postLogoutRedirectUris(SsoAdminValidation.redirectUris(in.postLogoutRedirectUris(), "POST_LOGOUT_URI_INVALID").toArray(String[]::new));
        }
        if (in.backchannelLogoutUri() != null) {
            b.backchannelLogoutUri(in.backchannelLogoutUri().isEmpty() ? null : SsoAdminValidation.backchannelUri(in.backchannelLogoutUri(), http));
        }
        String[] defaultGrants = base == null ? new String[]{GrantTypes.AUTHORIZATION_CODE, GrantTypes.REFRESH_TOKEN}
                : base.grantTypes().toArray(String[]::new);
        b.grantTypes(grantTypes(in.grantTypes(), defaultGrants));
        String[] defaultScopes = base == null ? new String[]{"openid", "profile", "email"} : base.scopes().toArray(String[]::new);
        b.scopes(scopes(in.scopes(), defaultScopes));
        if (in.firstParty() != null) {
            b.firstParty(in.firstParty());
        }
        return b;
    }

    private static ClientRegistration build(ClientRegistration.Builder b) {
        try {
            return b.build();
        } catch (SsoException e) {
            throw SsoApiException.badRequest(codeFor(e), "Client rejected");
        }
    }

    private boolean exists(String clientId) {
        MongoClientStore store = clientStore.getIfAvailable();
        return store != null && store.find(clientId).isPresent() || properties.getClients().containsKey(clientId)
                || settingsStore.find(clientId).isPresent();
    }

    @PostMapping
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> create(@PathVariable String tenantId, @RequestBody SsoAdminRequests.ClientInput body) {
        String adminId = scope.adminId();
        SsoTenantStore.Tenant t = scope.writableTenant(tenantId);
        MongoClientStore store = requireStore();
        if (body == null) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "Body is required");
        }
        return scope.exclusive("clients:" + t.id(), () -> create(adminId, t, store, body));
    }

    private ResponseEntity<Map<String, Object>> create(String adminId, SsoTenantStore.Tenant t, MongoClientStore store,
                                                       SsoAdminRequests.ClientInput body) {
        String clientId = body.clientId() == null ? "app-" + Bytes.randomToken(12).replace('_', 'x').replace('-', 'y') : body.clientId();
        if (!clientId.matches(CLIENT_ID_PATTERN)) {
            throw SsoApiException.badRequest("CLIENT_ID_INVALID", "clientId must match " + CLIENT_ID_PATTERN);
        }
        if (oidcClientCount(store, t.id()) >= t.quotas().oidcClients()) {
            throw new SsoApiException(HttpStatus.CONFLICT, "QUOTA_EXCEEDED", "OIDC client limit of the plan reached");
        }
        if (exists(clientId)) {
            throw new SsoApiException(HttpStatus.CONFLICT, "CLIENT_EXISTS", "Client already exists");
        }
        String type = body.type() == null ? "" : body.type().trim().toLowerCase(Locale.ROOT);
        if (!type.equals("public") && !type.equals("confidential")) {
            throw SsoApiException.badRequest("CLIENT_TYPE_INVALID", "type must be public or confidential");
        }
        ClientRegistration.Builder b = ClientRegistration.builder(t.id(), clientId).name(clientId);
        String secret = null;
        if (type.equals("public")) {
            b.publicClient();
        } else {
            secret = Bytes.randomToken(32);
            b.secret(ClientAuthMethod.CLIENT_SECRET_BASIC, secretHasher.getObject().hash(secret));
        }
        SsoClientSettingsStore.Settings settings = mergeSettings(clientId, t.id(), Optional.empty(), body);
        ClientRegistration reg = build(applyOidc(b, body, null));
        if (!store.create(reg)) {
            throw new SsoApiException(HttpStatus.CONFLICT, "CLIENT_EXISTS", "Client already exists");
        }
        settingsStore.save(settings);
        audit.record("admin.client.create", adminId, clientId, "success", Map.of("tenant", t.id(), "type", type));
        Map<String, Object> out = view(resolve(t.id(), clientId));
        if (secret != null) {
            out.put("clientSecret", secret);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @PatchMapping("/{clientId}")
    @RequiresStepUp
    public Map<String, Object> update(@PathVariable String tenantId, @PathVariable String clientId,
                                      @RequestBody SsoAdminRequests.ClientInput body) {
        String adminId = scope.adminId();
        SsoTenantStore.Tenant t = scope.writableTenant(tenantId);
        Resolved r = resolve(t.id(), clientId);
        if (body == null) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "Body is required");
        }
        if (body.clientId() != null && !body.clientId().equals(clientId) || body.type() != null) {
            throw SsoApiException.badRequest("IMMUTABLE_FIELD", "clientId and type cannot change");
        }
        ClientRegistration updated = null;
        if (touchesOidc(body)) {
            if (!r.managed()) {
                throw new SsoApiException(HttpStatus.CONFLICT, "CLIENT_CONFIGURED", "This client is configured in properties");
            }
            updated = build(applyOidc(r.oidc().toBuilder(), body, r.oidc()));
        }
        SsoClientSettingsStore.Settings settings = mergeSettings(clientId, t.id(), r.settings(), body);
        if (updated != null) {
            requireStore().save(updated);
        }
        settingsStore.save(settings);
        audit.record("admin.client.update", adminId, clientId, "success", Map.of("tenant", t.id(), "oidc", Boolean.toString(updated != null)));
        return view(resolve(t.id(), clientId));
    }

    @DeleteMapping("/{clientId}")
    @RequiresStepUp
    public ResponseEntity<Void> delete(@PathVariable String tenantId, @PathVariable String clientId) {
        String adminId = scope.adminId();
        SsoTenantStore.Tenant t = scope.tenant(tenantId);
        Resolved r = resolve(t.id(), clientId);
        if (!r.managed()) {
            throw new SsoApiException(HttpStatus.CONFLICT, "CLIENT_CONFIGURED", "This client is configured in properties");
        }
        requireStore().delete(clientId);
        settingsStore.delete(clientId);
        int revoked = 0;
        for (String id : tokenStore.idsByClient(clientId)) {
            try {
                tokens.revoke(id);
                revoked++;
            } catch (SsoException ignored) {
            }
        }
        audit.record("admin.client.delete", adminId, clientId, "success", Map.of("tenant", t.id(), "keysRevoked", Integer.toString(revoked)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{clientId}/secret")
    @RequiresStepUp
    public Map<String, Object> rotateSecret(@PathVariable String tenantId, @PathVariable String clientId) {
        String adminId = scope.adminId();
        SsoTenantStore.Tenant t = scope.writableTenant(tenantId);
        Resolved r = resolve(t.id(), clientId);
        MongoClientStore store = requireStore();
        if (!r.managed()) {
            throw new SsoApiException(HttpStatus.CONFLICT, "CLIENT_CONFIGURED", "This client is configured in properties");
        }
        ClientAuthMethod method = r.oidc().authMethod();
        if (r.oidc().type() != ClientType.CONFIDENTIAL || method == ClientAuthMethod.PRIVATE_KEY_JWT || method == ClientAuthMethod.NONE) {
            throw new SsoApiException(HttpStatus.CONFLICT, "CLIENT_NO_SECRET", "This client does not use a secret");
        }
        String secret = Bytes.randomToken(32);
        store.save(build(r.oidc().toBuilder().secret(method, secretHasher.getObject().hash(secret))));
        audit.record("admin.client.secret_rotate", adminId, clientId, "success", Map.of("tenant", t.id()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clientId", clientId);
        out.put("clientSecret", secret);
        return out;
    }
}
