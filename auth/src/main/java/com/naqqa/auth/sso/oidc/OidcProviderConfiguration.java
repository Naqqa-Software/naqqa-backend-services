package com.naqqa.auth.sso.oidc;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.SsoSecrets;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoClientPolicy;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.store.MongoRateLimitStore;
import com.naqqa.auth.sso.store.PersistentSecretBox;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.audit.AuditEntry;
import com.naqqa.sso.audit.AuditEvent;
import com.naqqa.sso.audit.AuditLog;
import com.naqqa.sso.audit.AuditStore;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.oidc.ClaimsSource;
import com.naqqa.sso.oidc.ClientAuthMethod;
import com.naqqa.sso.oidc.ClientRegistration;
import com.naqqa.sso.oidc.ClientStore;
import com.naqqa.sso.oidc.GrantTypes;
import com.naqqa.sso.oidc.OidcConfig;
import com.naqqa.sso.oidc.OidcProvider;
import com.naqqa.sso.password.PasswordHasher;
import com.naqqa.sso.session.RefreshTokenService;
import com.naqqa.sso.session.Session;
import com.naqqa.sso.session.SessionService;
import com.naqqa.sso.util.Bytes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Configuration
@ConditionalOnProperty(prefix = "naqqa.sso", name = {"enabled", "oidc-provider.enabled"}, havingValue = "true")
public class OidcProviderConfiguration {

    static String trimSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public static String issuer(SsoProperties properties) {
        String configured = properties.getOidcProvider().getIssuer();
        String issuer = trimSlash(configured != null && !configured.isBlank() ? configured : properties.getIssuer());
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("naqqa.sso.oidc-provider requires naqqa.sso.issuer");
        }
        return issuer;
    }

    @Bean
    public MongoOidcStore ssoOidcStore(MongoTemplate template, Clock clock, SsoProperties properties) {
        return new MongoOidcStore(template, clock, properties.getSession().getRefreshLifetime());
    }

    @Bean
    public MongoConsentStore ssoOidcConsentStore(MongoTemplate template) {
        return new MongoConsentStore(template);
    }

    @Bean
    public PasswordHasher ssoOidcSecretHasher(SsoSecrets secrets) {
        return new PasswordHasher(secrets.derived("naqqa-sso/oidc-client-secrets"), PasswordHasher.MIN_ITERATIONS);
    }

    @Bean
    public MongoClientStore ssoOidcClientStore(MongoTemplate template, SsoProperties properties, Environment environment,
                                               PasswordHasher ssoOidcSecretHasher) {
        List<ClientRegistration> configured = new ArrayList<>();
        properties.getOidcProvider().getClients().forEach((id, c) -> configured.add(client(id, c, environment, ssoOidcSecretHasher)));
        return new MongoClientStore(template, configured);
    }

    static ClientRegistration client(String id, SsoProperties.OidcClient c, Environment environment, PasswordHasher hasher) {
        ClientRegistration.Builder b = ClientRegistration.builder(c.getTenant() == null ? SsoSessionManager.TENANT : c.getTenant(), id)
                .name(c.getName() == null ? id : c.getName())
                .firstParty(c.isFirstParty())
                .requirePar(c.isRequirePar())
                .requireDpop(c.isRequireDpop())
                .requireState(c.isRequireState())
                .accessTokenTtl(c.getAccessTokenTtl())
                .idTokenTtl(c.getIdTokenTtl())
                .backchannelLogoutUri(c.getBackchannelLogoutUri())
                .minAcr(c.getMinAcr());
        if (c.isPublicClient()) {
            b.publicClient();
        } else {
            ClientAuthMethod method = ClientAuthMethod.fromValue(c.getAuthMethod());
            if (method == ClientAuthMethod.PRIVATE_KEY_JWT) {
                b.privateKeyJwt(c.getJwks());
            } else {
                String secret = c.getSecretEnv() == null ? null : environment.getProperty(c.getSecretEnv());
                if (secret == null || secret.isBlank()) {
                    throw new IllegalStateException("OIDC client " + id + " needs a secret in " + c.getSecretEnv());
                }
                b.secret(method, hasher.hash(secret.trim()));
            }
        }
        if (!c.getRedirectUris().isEmpty()) {
            b.redirectUris(c.getRedirectUris().toArray(String[]::new));
        }
        if (!c.getPostLogoutRedirectUris().isEmpty()) {
            b.postLogoutRedirectUris(c.getPostLogoutRedirectUris().toArray(String[]::new));
        }
        if (!c.getGrantTypes().isEmpty()) {
            b.grantTypes(c.getGrantTypes().toArray(String[]::new));
        } else if (c.getRedirectUris().isEmpty()) {
            b.grantTypes(GrantTypes.CLIENT_CREDENTIALS);
        }
        if (!c.getScopes().isEmpty()) {
            b.scopes(c.getScopes().toArray(String[]::new));
        }
        if (!c.getAllowedAcr().isEmpty()) {
            b.allowedAcr(c.getAllowedAcr().toArray(String[]::new));
        }
        if (!c.getAudience().isEmpty()) {
            b.audience(c.getAudience().toArray(String[]::new));
        }
        return b.build();
    }

    @Bean
    public OidcSigningKeys ssoOidcSigningKeys(SsoProperties properties, Environment environment, SigningKey authSigningKey,
                                              MongoTemplate template, PersistentSecretBox ssoSecretSealer, Clock clock) {
        SsoProperties.OidcProvider p = properties.getOidcProvider();
        String pem = p.getSigningKeyEnv() == null ? null : environment.getProperty(p.getSigningKeyEnv());
        return OidcSigningKeys.load(pem, p.getSigningAlgorithm(), authSigningKey, template, ssoSecretSealer, clock);
    }

    @Bean
    public OidcConfig ssoOidcConfig(SsoProperties properties, Environment environment) {
        SsoProperties.OidcProvider p = properties.getOidcProvider();
        OidcConfig d = OidcConfig.defaults(issuer(properties));
        Duration codeTtl = min(p.getAuthorizationCodeTtl(), Duration.ofMinutes(10));
        SsoProperties.DeviceCode device = properties.getDeviceCode();
        OidcConfig config = new OidcConfig(d.issuer(), d.authorizationEndpoint(), d.tokenEndpoint(), d.userinfoEndpoint(), d.jwksUri(),
                d.revocationEndpoint(), d.introspectionEndpoint(), d.deviceAuthorizationEndpoint(), d.endSessionEndpoint(),
                d.parEndpoint(), d.registrationEndpoint(), d.verificationUri(), d.defaultAudience(), p.getAccessTokenTtl(),
                p.getIdTokenTtl(), codeTtl, d.parTtl(), p.getPendingTtl(), device.getTtl(), Math.max(1, device.getPollInterval().getSeconds()),
                d.logoutTokenTtl(), d.clientAssertionMaxLifetime(), d.dpopIatSkew(), d.nonceReplayWindow(), false, null,
                d.deviceLookupsPerMinutePerIp(), d.deviceLookupsPerMinutePerSession(), d.acrValuesSupported());
        if (p.isDynamicRegistration()) {
            String token = p.getRegistrationTokenEnv() == null ? null : environment.getProperty(p.getRegistrationTokenEnv());
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("naqqa.sso.oidc-provider.dynamic-registration needs a token in " + p.getRegistrationTokenEnv());
            }
            config = config.withDynamicRegistration(OidcProvider.hashInitialAccessToken(token.trim()));
        }
        return config;
    }

    private static Duration min(Duration a, Duration b) {
        return a == null || a.compareTo(b) > 0 ? b : a;
    }

    @Bean
    public ClaimsSource ssoOidcClaims(UserRepository userRepository) {
        return (tenantId, userId) -> {
            if (!SsoSessionManager.TENANT.equals(tenantId) || userId == null) {
                return Optional.empty();
            }
            UserEntity user;
            try {
                user = userRepository.findById(Long.valueOf(userId)).orElse(null);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
            if (user == null || !user.isEnabled() || user.isBlocked()) {
                return Optional.empty();
            }
            Map<String, Object> claims = new LinkedHashMap<>();
            if (user.getEmail() != null) {
                claims.put("email", user.getEmail());
                claims.put("email_verified", user.isEmailVerified());
                claims.put("preferred_username", user.getEmail());
            }
            if (user.getFullName() != null) {
                claims.put("name", user.getFullName());
            }
            if (user.getPhoneNumber() != null) {
                claims.put("phone_number", user.getPhoneNumber());
            }
            return Optional.of(claims);
        };
    }

    static AuditLog forwardingAudit(SsoAudit audit) {
        AuditStore forward = new AuditStore() {
            @Override
            public void append(AuditEntry entry) {
                AuditEvent e = entry.event();
                audit.record(e.action(), e.actor(), e.target(), e.outcome(), e.attributes(), e.ip());
            }

            @Override
            public List<AuditEntry> all() {
                return List.of();
            }
        };
        return new AuditLog(forward, Map.of("forward", Bytes.random(32)), "forward");
    }

    static ClientRegistration withPolicyAcr(ClientRegistration client, SsoClientPolicy policy, SsoLoginFlow loginFlow, SessionService sessions) {
        if (policy == null || loginFlow == null || AssuranceEngine.clientAcrAtMaximum(client.minAcr())
                || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return client;
        }
        HttpServletRequest request = attributes.getRequest();
        String token = SsoWeb.cookie(request, SsoSessionManager.SESSION_COOKIE);
        if (token == null) {
            return client;
        }
        Session session;
        try {
            session = sessions.validate(token, SsoWeb.cookie(request, SsoLoginFlow.DEVICE_COOKIE));
        } catch (SsoException e) {
            return client;
        }
        UserEntity user = SsoSessionManager.TENANT.equals(session.tenantId()) ? loginFlow.loadUser(session.userId()) : null;
        if (user == null) {
            return client;
        }
        String needed = loginFlow.requiredAcr(user, session.idHash(), session.amr(), SsoWeb.cookie(request, SsoLoginFlow.REMEMBER_COOKIE),
                policy.forClient(client.clientId()));
        return AssuranceEngine.clientAcr(client.minAcr(), client.allowedAcr(), needed)
                .map(r -> client.toBuilder().allowedAcr(r.allowedAcr().toArray(String[]::new)).minAcr(r.minAcr()).build())
                .orElse(client);
    }

    static ClientStore enabledClients(MongoClientStore clients, ObjectProvider<SsoClientPolicy> policy, ObjectProvider<SsoLoginFlow> loginFlow,
                                      SessionService sessions) {
        return new ClientStore() {
            @Override
            public Optional<ClientRegistration> find(String clientId) {
                SsoClientPolicy p = policy.getIfAvailable();
                return clients.find(clientId).filter(c -> p == null || p.enabled(c.clientId()))
                        .map(c -> withPolicyAcr(c, p, loginFlow.getIfAvailable(), sessions));
            }

            @Override
            public void save(ClientRegistration client) {
                clients.save(client);
            }

            @Override
            public boolean delete(String clientId) {
                return clients.delete(clientId);
            }
        };
    }

    @Bean
    public OidcProvider ssoOidcProvider(Clock clock, OidcConfig ssoOidcConfig, MongoClientStore clients, MongoConsentStore consents,
                                        MongoOidcStore store, SessionService sessions, RefreshTokenService refreshTokens,
                                        ClaimsSource ssoOidcClaims, OidcSigningKeys keys, PasswordHasher ssoOidcSecretHasher,
                                        MongoRateLimitStore rateLimits, SsoAudit audit, SsoProperties properties,
                                        ObjectProvider<SsoClientPolicy> clientPolicy, ObjectProvider<SsoLoginFlow> loginFlow) {
        return OidcProvider.builder(clock, ssoOidcConfig)
                .clients(enabledClients(clients, clientPolicy, loginFlow, sessions))
                .consents(consents)
                .store(store)
                .sessions(sessions)
                .refreshTokens(refreshTokens)
                .claims(ssoOidcClaims)
                .keyRing(keys.ring())
                .defaultAlg(keys.ring().active().alg())
                .secretHasher(ssoOidcSecretHasher)
                .rateLimitStore(rateLimits)
                .audit(forwardingAudit(audit))
                .sidRevocationTtl(properties.getSession().getAbsoluteTimeout().plusDays(1))
                .build();
    }

    @Bean(destroyMethod = "close")
    public BackchannelLogoutDispatcher ssoBackchannelLogoutDispatcher(SsoProperties properties, SsoAudit audit) {
        return new BackchannelLogoutDispatcher(properties.getOidcProvider().getBackchannel(), audit);
    }
}
