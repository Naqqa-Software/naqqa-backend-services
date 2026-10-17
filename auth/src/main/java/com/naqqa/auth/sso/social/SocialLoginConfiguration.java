package com.naqqa.auth.sso.social;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.sso.crypto.Pem;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.oauth.AppleClientSecret;
import com.naqqa.sso.oauth.ClientSecretSource;
import com.naqqa.sso.oauth.OAuthClient;
import com.naqqa.sso.oauth.ProviderConfig;
import com.naqqa.sso.oauth.ProviderKind;
import com.naqqa.sso.oauth.ProviderRegistry;
import com.naqqa.sso.oauth.SafeHttp;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Configuration
@ConditionalOnProperty(prefix = "naqqa.sso", name = {"enabled", "social.enabled"}, havingValue = "true")
public class SocialLoginConfiguration {

    public record SocialProviders(ProviderRegistry registry, Map<String, ProviderConfig> configs) {
    }

    @Bean
    public MongoOAuthStateStore ssoOAuthStateStore(MongoTemplate template) {
        return new MongoOAuthStateStore(template);
    }

    @Bean
    public SocialProviders ssoSocialProviders(SsoProperties properties, Environment environment, Clock clock) {
        List<ProviderConfig> configs = new ArrayList<>();
        String issuer = properties.getIssuer() == null ? "" : properties.getIssuer().replaceAll("/+$", "");
        properties.getSocial().getRegistrations().forEach((id, p) -> configs.add(provider(id, p, issuer, environment, clock)));
        Map<String, ProviderConfig> byId = new java.util.LinkedHashMap<>();
        configs.forEach(c -> byId.put(c.id(), c));
        return new SocialProviders(ProviderRegistry.of(configs), Map.copyOf(byId));
    }

    @Bean
    public OAuthClient ssoOAuthClient(SocialProviders providers, MongoOAuthStateStore states, SsoProperties properties, Clock clock) {
        SafeHttp http = properties.getSocial().isTestMode() ? new SafeHttp(SafeHttp.Options.forTests()) : SafeHttp.production();
        return new OAuthClient(providers.registry(), states, http, clock);
    }

    static String secret(Environment environment, String env) {
        if (env == null || env.isBlank()) {
            return null;
        }
        String v = environment.getProperty(env);
        return v == null || v.isBlank() ? null : v.trim();
    }

    static ProviderConfig provider(String id, SsoProperties.SocialProvider p, String issuer, Environment environment, Clock clock) {
        String kind = p.getKind() == null ? id.toLowerCase(Locale.ROOT) : p.getKind().toLowerCase(Locale.ROOT);
        String redirect = p.getRedirectUri() != null && !p.getRedirectUri().isBlank() ? p.getRedirectUri() : issuer + "/login/oauth2/code/" + id;
        String secret = secret(environment, p.getClientSecretEnv());
        if (!kind.equals("oidc") && !kind.equals(id)) {
            throw new IllegalStateException("social registration " + id + " must be named after its kind " + kind);
        }
        ProviderConfig base = switch (kind) {
            case "google" -> ProviderConfig.google(p.getClientId(), secret, redirect);
            case "github" -> ProviderConfig.github(p.getClientId(), secret, redirect);
            case "facebook" -> ProviderConfig.facebook(p.getClientId(), secret, redirect);
            case "microsoft" -> ProviderConfig.microsoft(p.getClientId(), secret, redirect, p.getTenant(),
                    new LinkedHashSet<>(p.getAllowedTenants()));
            case "apple" -> ProviderConfig.apple(p.getClientId(), appleSecret(p, environment, clock), redirect);
            case "oidc" -> ProviderConfig.builder(id, ProviderKind.GENERIC_OIDC)
                    .issuer(p.getIssuer())
                    .clientId(p.getClientId())
                    .clientSecret(secret == null ? null : ClientSecretSource.fixed(secret))
                    .redirectUri(redirect)
                    .scopes(List.of("openid", "email", "profile"))
                    .authorizationEndpoint(p.getAuthorizationEndpoint())
                    .tokenEndpoint(p.getTokenEndpoint())
                    .jwksUri(p.getJwksUri())
                    .build();
            default -> throw new IllegalStateException("unknown social provider kind " + kind);
        };
        ProviderConfig.Builder b = base.toBuilder();
        if (p.getIssuer() != null && !p.getIssuer().isBlank()) {
            b.issuer(p.getIssuer());
            if (!base.issuerAliases().isEmpty()) {
                b.issuerAliases(java.util.Set.of());
            }
        }
        if (p.getAuthorizationEndpoint() != null) {
            b.authorizationEndpoint(p.getAuthorizationEndpoint());
        }
        if (p.getTokenEndpoint() != null) {
            b.tokenEndpoint(p.getTokenEndpoint());
        }
        if (p.getJwksUri() != null) {
            b.jwksUri(p.getJwksUri());
        }
        if (p.getUserinfoEndpoint() != null) {
            b.userinfoEndpoint(p.getUserinfoEndpoint());
        }
        if (p.getEmailsEndpoint() != null) {
            b.emailsEndpoint(p.getEmailsEndpoint());
        }
        if (p.getDebugTokenEndpoint() != null) {
            b.debugTokenEndpoint(p.getDebugTokenEndpoint());
        }
        if (!p.getScopes().isEmpty()) {
            b.scopes(List.copyOf(p.getScopes()));
        }
        if (!p.getTrustedEmailDomains().isEmpty()) {
            b.trustedEmailDomains(new LinkedHashSet<>(p.getTrustedEmailDomains()));
        }
        if (p.getTrustAllEmails() != null) {
            b.trustAllEmails(p.getTrustAllEmails());
        }
        return b.build();
    }

    static AppleClientSecret appleSecret(SsoProperties.SocialProvider p, Environment environment, Clock clock) {
        String pem = secret(environment, p.getApplePrivateKeyEnv());
        if (pem == null) {
            throw new IllegalStateException("social registration apple needs a team key in " + p.getApplePrivateKeyEnv());
        }
        int split = pem.indexOf("-----BEGIN PUBLIC KEY-----");
        if (split <= 0) {
            throw new IllegalStateException("social registration apple needs the private and the public key PEM");
        }
        SigningKey key = Pem.signingKey(pem.substring(0, split).trim(), pem.substring(split).trim());
        return new AppleClientSecret(p.getAppleTeamId(), p.getAppleKeyId(), p.getClientId(), key, clock, AppleClientSecret.DEFAULT_LIFETIME);
    }
}
