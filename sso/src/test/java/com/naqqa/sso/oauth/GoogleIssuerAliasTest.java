package com.naqqa.sso.oauth;

import com.naqqa.sso.crypto.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.naqqa.sso.oauth.Support.CLIENT;
import static com.naqqa.sso.oauth.Support.codeOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GoogleIssuerAliasTest {
    private MutableClock clock;
    private FakeIdp idp;
    private OAuthClient client;

    @BeforeEach
    void up() throws Exception {
        clock = Support.clock();
        idp = new FakeIdp(clock);
        ProviderConfig cfg = ProviderConfig.builder("google", ProviderKind.GOOGLE)
                .issuer(idp.base)
                .clientId(CLIENT)
                .clientSecret(ClientSecretSource.fixed("s3cret-value"))
                .redirectUri(Support.REDIRECT)
                .scopes(List.of("openid", "email"))
                .authorizationEndpoint(idp.base + "/authorize")
                .tokenEndpoint(idp.base + "/token")
                .jwksUri(idp.base + "/jwks")
                .issuerAliases(Set.of(ProviderConfig.GOOGLE_ISSUER_ALIAS))
                .build();
        client = Support.client(clock, new InMemoryStateStore(clock), cfg);
    }

    @AfterEach
    void down() {
        idp.close();
    }

    private String run(String iss) {
        Support.Started s = Support.start(client, "google");
        Map<String, Object> c = idp.idClaims(CLIENT, "sub-g", s.nonce());
        c.put("iss", iss);
        idp.respondTokens(idp.sign(c), "at");
        return client.complete(Support.cb(s.state(), "c"), s.binding()).subject();
    }

    @Test
    @DisplayName("SSO-T-oauth-224 F-R2 google bare issuer alias accounts.google.com accepted")
    void bareAlias() {
        assertEquals("sub-g", run("accounts.google.com"));
    }

    @Test
    @DisplayName("SSO-T-oauth-225 F-R2 google configured issuer still accepted")
    void primary() {
        assertEquals("sub-g", run(idp.base));
    }

    @Test
    @DisplayName("SSO-T-oauth-226 F-R2 any other issuer variant rejected")
    void others() {
        for (String iss : List.of("http://accounts.google.com", "accounts.google.com/", "ACCOUNTS.GOOGLE.COM", "https://accounts.google.com.evil.example", "evil.example", "")) {
            assertEquals("jwt.issuer_mismatch", codeOf(() -> run(iss)), iss);
        }
    }

    @Test
    @DisplayName("SSO-T-oauth-227 F-R2 issuer aliases only allowed for the google preset with the exact alias")
    void aliasConfig() {
        assertEquals("oauth.config_invalid", codeOf(() -> Support.generic(idp, "x").issuerAliases(Set.of("accounts.google.com")).build()));
        assertEquals("oauth.config_invalid", codeOf(() -> ProviderConfig.builder("google", ProviderKind.GOOGLE).issuer("https://accounts.google.com")
                .clientId("c").redirectUri("https://a/cb").authorizationEndpoint("https://a/x").tokenEndpoint("https://a/t").jwksUri("https://a/j")
                .issuerAliases(Set.of("evil.example")).build()));
        assertEquals(Set.of("accounts.google.com"), ProviderConfig.google("c", "s", "https://a/cb").issuerAliases());
        assertEquals(Set.of(), ProviderConfig.apple("c", null, "https://a/cb").issuerAliases());
    }
}
