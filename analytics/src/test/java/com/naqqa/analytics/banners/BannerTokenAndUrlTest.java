package com.naqqa.analytics.banners;

import com.naqqa.analytics.banners.model.BannerDestination;
import com.naqqa.analytics.banners.security.BannerTokenService;
import com.naqqa.analytics.banners.security.BannerTokenService.Claims;
import com.naqqa.analytics.banners.security.BannerUrlPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BannerTokenAndUrlTest {

    private final Instant now = Instant.parse("2026-10-07T10:00:00Z");
    private final BannerTokenService tokens = new BannerTokenService("test-secret", Duration.ofDays(30));
    private final Claims claims = new Claims("camp1", "cr1", "home_side", "ro", "vid-1", "sid-1", "b1", "home", now);

    @Test
    void signVerifyRoundTrip() {
        String token = tokens.sign(claims);
        assertThat(token).doesNotContain("/", "+", "=");
        Claims back = tokens.verify(token, now.plusSeconds(10));
        assertThat(back).isEqualTo(claims);
    }

    @Test
    void tamperedTokenRejected() {
        String token = tokens.sign(claims);
        String other = tokens.sign(new Claims("camp2", "cr1", "home_side", "ro", "vid-1", "sid-1", "b1", "home", now));
        String forged = other.substring(0, other.indexOf('.')) + token.substring(token.indexOf('.'));
        assertThat(tokens.verify(forged, now)).isNull();
        int dot = token.indexOf('.');
        char first = token.charAt(dot + 1);
        assertThat(tokens.verify(token.substring(0, dot + 1) + (first == 'A' ? 'Q' : 'A') + token.substring(dot + 2), now)).isNull();
        assertThat(tokens.verify("garbage", now)).isNull();
        assertThat(tokens.verify("a.b.c", now)).isNull();
        assertThat(tokens.verify(null, now)).isNull();
    }

    @Test
    void differentSecretRejected() {
        String token = new BannerTokenService("other", Duration.ofDays(30)).sign(claims);
        assertThat(tokens.verify(token, now)).isNull();
    }

    @Test
    void expiredAndFutureTokensRejected() {
        String token = tokens.sign(claims);
        assertThat(tokens.verify(token, now.plus(Duration.ofDays(31)))).isNull();
        assertThat(tokens.verify(token, now.minus(Duration.ofMinutes(10)))).isNull();
    }

    @Test
    void pipeInValuesCannotInjectFields() {
        Claims evil = new Claims("camp1|x", "cr1", "slot", null, null, null, null, null, now);
        Claims back = tokens.verify(tokens.sign(evil), now);
        assertThat(back.campaignId()).isEqualTo("camp1x");
        assertThat(back.lang()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://partner.md", "https://partner.md/oferta?x=1#top", "https://sub.partner.co.uk/a/b"})
    void validExternal(String url) {
        assertThat(BannerUrlPolicy.validExternal(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://partner.md", "javascript:alert(1)", "JaVaScRiPt:alert(1)", "https://user:pw@partner.md",
            "ftp://partner.md", "//partner.md", "https://localhost", "https://partner.md/\\evil", "data:text/html,x",
            "https://partner.md/ x", " javascript:alert(1)", "https://partner.md/?u=javascript:alert(1)"})
    void invalidExternal(String url) {
        assertThat(BannerUrlPolicy.validExternal(url)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/promotions/lapte", "/ro/company/kaufland?tab=promo", "/blogs/x#a"})
    void validInternal(String path) {
        assertThat(BannerUrlPolicy.validInternal(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"//evil.com", "/\\evil.com", "https://evil.com", "promotions", "/%2F%2Fevil.com", "/%5cevil.com",
            "javascript:alert(1)", "/javascript:alert(1)", "/a\tb"})
    void invalidInternal(String path) {
        assertThat(BannerUrlPolicy.validInternal(path)).isFalse();
    }

    @Test
    void resolveAddsLanguagePrefixForInternalOnly() {
        BannerDestination internal = new BannerDestination(BannerDestination.Type.INTERNAL, "/promotions/x", null);
        assertThat(BannerUrlPolicy.resolve(internal, "ru", true)).isEqualTo("/ru/promotions/x");
        assertThat(BannerUrlPolicy.resolve(internal, "ru", false)).isEqualTo("/promotions/x");
        BannerDestination prefixed = new BannerDestination(BannerDestination.Type.INTERNAL, "/ro/promotions/x", null);
        assertThat(BannerUrlPolicy.resolve(prefixed, "ru", true)).isEqualTo("/ro/promotions/x");
        BannerDestination root = new BannerDestination(BannerDestination.Type.INTERNAL, "/", null);
        assertThat(BannerUrlPolicy.resolve(root, "ru", true)).isEqualTo("/ru");
        BannerDestination external = new BannerDestination(BannerDestination.Type.EXTERNAL, null, "https://partner.md/x");
        assertThat(BannerUrlPolicy.resolve(external, "ru", true)).isEqualTo("https://partner.md/x");
        BannerDestination bad = new BannerDestination(BannerDestination.Type.EXTERNAL, null, "javascript:alert(1)");
        assertThat(BannerUrlPolicy.resolve(bad, "ru", true)).isNull();
        BannerDestination mixed = new BannerDestination(BannerDestination.Type.EXTERNAL, "/ok", null);
        assertThat(BannerUrlPolicy.resolve(mixed, "ro", true)).isNull();
    }
}
