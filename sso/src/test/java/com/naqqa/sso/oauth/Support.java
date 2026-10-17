package com.naqqa.sso.oauth;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.MutableClock;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class Support {
    static final String CLIENT = "client-1";
    static final String REDIRECT = "https://app.example/cb";
    static final Instant START = Instant.parse("2026-10-15T10:00:00Z");

    record Started(OAuthClient.Begin begin, Map<String, String> query) {
        String state() {
            return query.get("state");
        }

        String nonce() {
            return query.get("nonce");
        }

        String binding() {
            return begin.binding();
        }
    }

    private Support() {
    }

    static MutableClock clock() {
        return new MutableClock(START);
    }

    static Map<String, String> query(String url) {
        String q = URI.create(url).getRawQuery();
        Map<String, String> m = new LinkedHashMap<>();
        for (String p : q.split("&")) {
            int i = p.indexOf('=');
            m.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static Started start(OAuthClient c, String provider) {
        OAuthClient.Begin b = c.begin(provider, "/after");
        return new Started(b, query(b.authorizationUrl()));
    }

    static ProviderConfig.Builder generic(FakeIdp idp, String id) {
        return ProviderConfig.builder(id, ProviderKind.GENERIC_OIDC)
                .issuer(idp.base)
                .clientId(CLIENT)
                .clientSecret(ClientSecretSource.fixed("s3cret-value"))
                .redirectUri(REDIRECT)
                .scopes(List.of("openid", "email", "profile"))
                .authorizationEndpoint(idp.base + "/authorize")
                .tokenEndpoint(idp.base + "/token")
                .jwksUri(idp.base + "/jwks");
    }

    static OAuthClient client(MutableClock clock, StateStore store, ProviderConfig... configs) {
        return new OAuthClient(ProviderRegistry.of(List.of(configs)), store, new SafeHttp(SafeHttp.Options.forTests()), clock);
    }

    static CallbackParams cb(String state, String code) {
        Map<String, String> m = new LinkedHashMap<>();
        if (state != null) {
            m.put("state", state);
        }
        if (code != null) {
            m.put("code", code);
        }
        return CallbackParams.of(m);
    }

    static String codeOf(Runnable r) {
        return assertThrows(SsoException.class, r::run).code();
    }
}
