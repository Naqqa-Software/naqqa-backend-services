package com.naqqa.analytics;

import com.naqqa.analytics.collect.BotDetector;
import com.naqqa.analytics.collect.UserAgentParser;
import com.naqqa.analytics.collect.UserAgentParser.UserAgentInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsBotAndUserAgentTest {

    private final BotDetector bots = BotDetector.defaults();

    @Test
    void knownBotsAreDetected() {
        assertThat(bots.userAgent("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)", null)).isEqualTo(BotDetector.UA);
        assertThat(bots.userAgent("curl/8.4.0", null)).isEqualTo(BotDetector.UA);
        assertThat(bots.userAgent("WhatsApp/2.23.20.0", null)).isEqualTo(BotDetector.UA);
        assertThat(bots.userAgent("facebookexternalhit/1.1", null)).isEqualTo(BotDetector.UA);
        assertThat(bots.userAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 HeadlessChrome/120.0.0.0 Safari/537.36", null))
                .isEqualTo(BotDetector.HEADLESS);
        assertThat(bots.userAgent(null, null)).isEqualTo(BotDetector.EMPTY_UA);
        assertThat(bots.userAgent(AnalyticsTestSupport.CHROME, true)).isEqualTo(BotDetector.WEBDRIVER);
    }

    @Test
    void humansAndAppsAreNotBots() {
        assertThat(bots.userAgent(AnalyticsTestSupport.CHROME, false)).isNull();
        assertThat(bots.userAgent(AnalyticsTestSupport.IPHONE, null)).isNull();
        assertThat(bots.userAgent("okhttp/4.12.0", null)).isNull();
        assertThat(new BotDetector(List.of("MyScanner"), 5, 300, 30).userAgent("Agent MyScanner 1.0", null)).isEqualTo(BotDetector.UA);
    }

    @Test
    void tooFastAndNoInteractionHeuristics() {
        assertThat(bots.tooFast(List.of(0L, 100L, 200L, 300L, 400L))).isTrue();
        assertThat(bots.tooFast(List.of(0L, 5_000L, 10_000L, 15_000L, 20_000L))).isFalse();
        assertThat(bots.tooFast(List.of(0L, 10L, 20L))).isFalse();
        assertThat(bots.noInteraction(30, 0)).isTrue();
        assertThat(bots.noInteraction(30, 1)).isFalse();
        assertThat(bots.noInteraction(3, 0)).isFalse();
        assertThat(BotDetector.isInteraction("page_leave", 0L)).isFalse();
        assertThat(BotDetector.isInteraction("page_leave", 1200L)).isTrue();
        assertThat(BotDetector.isInteraction("item_click", null)).isTrue();
    }

    @Test
    void userAgentParsing() {
        assertThat(UserAgentParser.parse(AnalyticsTestSupport.IPHONE)).isEqualTo(new UserAgentInfo("mobile", "iOS", "Safari"));
        assertThat(UserAgentParser.parse(AnalyticsTestSupport.CHROME)).isEqualTo(new UserAgentInfo("desktop", "Windows", "Chrome"));
        assertThat(UserAgentParser.parse("Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"))
                .isEqualTo(new UserAgentInfo("mobile", "Android", "Chrome"));
        assertThat(UserAgentParser.parse("Mozilla/5.0 (Linux; Android 13; SM-X200) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"))
                .isEqualTo(new UserAgentInfo("tablet", "Android", "Chrome"));
        assertThat(UserAgentParser.parse("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.0.0").browser())
                .isEqualTo("Edge");
        assertThat(UserAgentParser.parse("Mozilla/5.0 (Macintosh; Intel Mac OS X 14.5; rv:130.0) Gecko/20100101 Firefox/130.0"))
                .isEqualTo(new UserAgentInfo("desktop", "macOS", "Firefox"));
        assertThat(UserAgentParser.parse("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/25.0 Chrome/121.0.0.0 Mobile Safari/537.36").browser())
                .isEqualTo("Samsung Internet");
        assertThat(UserAgentParser.parse("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 YaBrowser/24.7.0.0 Safari/537.36").browser())
                .isEqualTo("Yandex");
        assertThat(UserAgentParser.parse("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 [FBAN/FBIOS;FBAV/470.0]").browser())
                .isEqualTo("Facebook");
        assertThat(UserAgentParser.parse("OMY/2.4.0 (Linux; Android 14; Pixel 8) Mobile okhttp/4.12.0")).isEqualTo(new UserAgentInfo("mobile", "Android", "App"));
        assertThat(UserAgentParser.parse("OMY/2.4.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) CFNetwork/1494 Darwin/23.4.0")).isEqualTo(new UserAgentInfo("mobile", "iOS", "App"));
        assertThat(UserAgentParser.parse("Mozilla/5.0 (compatible; Googlebot/2.1)").device()).isEqualTo("bot");
        assertThat(UserAgentParser.parse("").device()).isEqualTo("Other");
    }
}
