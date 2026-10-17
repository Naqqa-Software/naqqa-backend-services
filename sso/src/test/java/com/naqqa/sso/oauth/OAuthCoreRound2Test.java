package com.naqqa.sso.oauth;

import com.naqqa.sso.crypto.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.naqqa.sso.oauth.Support.CLIENT;
import static com.naqqa.sso.oauth.Support.codeOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OAuthCoreRound2Test {
    private MutableClock clock;
    private FakeIdp idp;
    private final AtomicInteger evilTokenHits = new AtomicInteger();

    @BeforeEach
    void up() throws Exception {
        clock = Support.clock();
        idp = new FakeIdp(clock);
        idp.route("/evil/token", (ex, b) -> {
            evilTokenHits.incrementAndGet();
            return FakeIdp.Resp.json(400, "{\"error\":\"invalid_grant\"}");
        });
    }

    @AfterEach
    void down() {
        idp.close();
    }

    private CallbackParams cb(String state, String code, String iss) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("code", code);
        if (iss != null) {
            m.put("iss", iss);
        }
        return CallbackParams.of(m);
    }

    @Test
    @DisplayName("SSO-T-oauth-233 mix-up: shared callback without iss never sends the code to the provider named by state")
    void sharedCallbackWithoutIss() {
        ProviderConfig honest = Support.generic(idp, "honest").pkce(true).build();
        ProviderConfig evil = Support.generic(idp, "evil").issuer("https://evil.example").pkce(true)
                .tokenEndpoint(idp.base + "/evil/token").build();
        OAuthClient client = Support.client(clock, new InMemoryStateStore(clock), honest, evil);
        Support.Started s = Support.start(client, "evil");
        assertEquals("oauth.provider_required", codeOf(() -> client.complete(cb(s.state(), "honest-code", null), s.binding())));
        assertEquals(0, evilTokenHits.get());
        Support.Started s2 = Support.start(client, "evil");
        assertEquals("oauth.issuer_mismatch", codeOf(() -> client.complete(cb(s2.state(), "honest-code", idp.base), s2.binding())));
        assertEquals(0, evilTokenHits.get());
        Support.Started s3 = Support.start(client, "evil");
        assertEquals("oauth.token_exchange_failed",
                codeOf(() -> client.complete(cb(s3.state(), "evil-code", "https://evil.example"), s3.binding())));
        assertEquals(1, evilTokenHits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-234 RFC 9207: a provider that advertises authorization_response_iss_parameter_supported must send iss")
    void issRequiredWhenAdvertised() {
        idp.route("/.well-known/openid-configuration", (ex, b) -> FakeIdp.Resp.json("{\"issuer\":\"" + idp.base
                + "\",\"authorization_endpoint\":\"" + idp.base + "/authorize\",\"token_endpoint\":\"" + idp.base
                + "/token\",\"jwks_uri\":\"" + idp.base + "/jwks\",\"authorization_response_iss_parameter_supported\":true}"));
        ProviderConfig corp = ProviderConfig.oidc("corp", idp.base, CLIENT, "s", Support.REDIRECT, null);
        OAuthClient client = Support.client(clock, new InMemoryStateStore(clock), corp);
        Support.Started s = Support.start(client, "corp");
        assertEquals("oauth.issuer_missing", codeOf(() -> client.complete("corp", cb(s.state(), "c", null), s.binding())));
        assertEquals(0, idp.tokenHits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-235 iss on a tenant-template provider must match the template, never skipped")
    void issTemplate() {
        ProviderConfig ms = ProviderConfig.microsoft(CLIENT, "s", Support.REDIRECT, "common", null).toBuilder()
                .authorizationEndpoint(idp.base + "/authorize").tokenEndpoint(idp.base + "/token").jwksUri(idp.base + "/jwks")
                .build();
        OAuthClient client = Support.client(clock, new InMemoryStateStore(clock), ms);
        Support.Started s = Support.start(client, "microsoft");
        assertEquals("oauth.issuer_mismatch", codeOf(() -> client.complete("microsoft", cb(s.state(), "c", "https://evil.example"),
                s.binding())));
        Support.Started s2 = Support.start(client, "microsoft");
        assertEquals("oauth.issuer_mismatch", codeOf(() -> client.complete("microsoft",
                cb(s2.state(), "c", "https://login.microsoftonline.com/x/../evil/v2.0"), s2.binding())));
        assertEquals(0, idp.tokenHits.get());
    }
}
