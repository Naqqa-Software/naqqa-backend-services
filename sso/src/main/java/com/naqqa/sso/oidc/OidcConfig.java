package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;

import java.net.URI;
import java.time.Duration;
import java.util.List;

public record OidcConfig(String issuer, String authorizationEndpoint, String tokenEndpoint, String userinfoEndpoint,
                         String jwksUri, String revocationEndpoint, String introspectionEndpoint,
                         String deviceAuthorizationEndpoint, String endSessionEndpoint, String parEndpoint,
                         String registrationEndpoint, String verificationUri, List<String> defaultAudience,
                         Duration accessTokenTtl, Duration idTokenTtl, Duration codeTtl, Duration parTtl,
                         Duration pendingTtl, Duration deviceCodeTtl, long deviceIntervalSeconds, Duration logoutTokenTtl,
                         Duration clientAssertionMaxLifetime, Duration dpopIatSkew, Duration nonceReplayWindow,
                         boolean dynamicRegistrationEnabled, String registrationInitialAccessTokenHash,
                         int deviceLookupsPerMinutePerIp, int deviceLookupsPerMinutePerSession, List<String> acrValuesSupported) {

    public static final String BASE_PATH = "/oauth2";

    public OidcConfig {
        if (issuer == null) {
            throw new SsoException("oidc.config_invalid");
        }
        URI u = URI.create(issuer);
        boolean loopback = "localhost".equals(u.getHost()) || "127.0.0.1".equals(u.getHost());
        if (!"https".equals(u.getScheme()) && !("http".equals(u.getScheme()) && loopback)) {
            throw new SsoException("oidc.issuer_insecure");
        }
        if (u.getRawQuery() != null || u.getRawFragment() != null || issuer.endsWith("/")) {
            throw new SsoException("oidc.config_invalid");
        }
        defaultAudience = List.copyOf(defaultAudience == null ? List.of() : defaultAudience);
        acrValuesSupported = List.copyOf(acrValuesSupported == null ? List.of() : acrValuesSupported);
        if (codeTtl.compareTo(Duration.ofMinutes(10)) > 0 || parTtl.compareTo(Duration.ofMinutes(10)) > 0
                || clientAssertionMaxLifetime.compareTo(Duration.ofMinutes(5)) > 0 || deviceIntervalSeconds < 1) {
            throw new SsoException("oidc.config_invalid");
        }
    }

    public static OidcConfig defaults(String issuer) {
        String b = issuer + BASE_PATH;
        return new OidcConfig(issuer, b + "/authorize", b + "/token", b + "/userinfo", b + "/jwks", b + "/revoke",
                b + "/introspect", b + "/device_authorization", b + "/logout", b + "/par", b + "/register", b + "/device",
                List.of(issuer), Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofSeconds(60), Duration.ofSeconds(60),
                Duration.ofMinutes(10), Duration.ofMinutes(10), 5, Duration.ofMinutes(2), Duration.ofMinutes(5),
                Duration.ofSeconds(60), Duration.ofMinutes(10), false, null, 10, 5,
                List.of("urn:naqqa:acr:1", "urn:naqqa:acr:2", "urn:naqqa:acr:3"));
    }

    public OidcConfig withDynamicRegistration(String initialAccessTokenHash) {
        return new OidcConfig(issuer, authorizationEndpoint, tokenEndpoint, userinfoEndpoint, jwksUri, revocationEndpoint,
                introspectionEndpoint, deviceAuthorizationEndpoint, endSessionEndpoint, parEndpoint, registrationEndpoint,
                verificationUri, defaultAudience, accessTokenTtl, idTokenTtl, codeTtl, parTtl, pendingTtl, deviceCodeTtl,
                deviceIntervalSeconds, logoutTokenTtl, clientAssertionMaxLifetime, dpopIatSkew, nonceReplayWindow, true,
                initialAccessTokenHash, deviceLookupsPerMinutePerIp, deviceLookupsPerMinutePerSession, acrValuesSupported);
    }

    public String discoveryUrl() {
        return issuer + "/.well-known/openid-configuration";
    }
}
