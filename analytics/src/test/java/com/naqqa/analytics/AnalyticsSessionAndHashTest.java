package com.naqqa.analytics;

import com.naqqa.analytics.collect.ChannelClassifier;
import com.naqqa.analytics.collect.ChannelClassifier.Attribution;
import com.naqqa.analytics.collect.CookielessHasher;
import com.naqqa.analytics.collect.IpAnonymizer;
import com.naqqa.analytics.collect.MemoryKeyValueStore;
import com.naqqa.analytics.collect.RateLimiter;
import com.naqqa.analytics.collect.SessionState;
import com.naqqa.analytics.collect.Sessionizer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsSessionAndHashTest {

    private final Sessionizer s = new Sessionizer(30 * 60_000L, AnalyticsTestSupport.ZONE);
    private final Attribution direct = new Attribution(ChannelClassifier.DIRECT, null, null, null);
    private final Attribution google = new Attribution(ChannelClassifier.ORGANIC, "google", "organic", null);
    private final Attribution facebook = new Attribution(ChannelClassifier.SOCIAL, "facebook", "social", null);

    private SessionState state(long last, Attribution a) {
        return new SessionState().sid("abc123").clientSid("abc123").lastMs(last).startMs(last).day(s.day(last)).attribution(a)
                .campaignKey(a.key());
    }

    @Test
    void sessionRules() {
        long t = ZonedDateTime.of(2026, 10, 3, 12, 0, 0, 0, AnalyticsTestSupport.ZONE).toInstant().toEpochMilli();
        assertThat(s.decide(null, t, direct)).isEqualTo(new Sessionizer.Decision(true, Sessionizer.FIRST));
        SessionState st = state(t, google);
        assertThat(s.decide(st, t + 29 * 60_000L, direct).newSession()).isFalse();
        assertThat(s.decide(st, t + 29 * 60_000L, google).newSession()).isFalse();
        assertThat(s.decide(st, t + 31 * 60_000L, direct)).isEqualTo(new Sessionizer.Decision(true, Sessionizer.INACTIVITY));
        assertThat(s.decide(st, t + 60_000L, facebook)).isEqualTo(new Sessionizer.Decision(true, Sessionizer.CAMPAIGN));
        assertThat(s.decide(st, t - 60_000L, facebook).newSession()).isFalse();
    }

    @Test
    void midnightInConfiguredTimezoneStartsNewSession() {
        long beforeMidnight = ZonedDateTime.of(2026, 10, 3, 23, 50, 0, 0, AnalyticsTestSupport.ZONE).toInstant().toEpochMilli();
        SessionState st = state(beforeMidnight, direct);
        assertThat(s.decide(st, beforeMidnight + 15 * 60_000L, direct)).isEqualTo(new Sessionizer.Decision(true, Sessionizer.MIDNIGHT));
        assertThat(s.decide(st, beforeMidnight + 5 * 60_000L, direct).newSession()).isFalse();
    }

    @Test
    void rotatedSidKeepsClientPrefix() {
        assertThat(Sessionizer.nextSid("client-sid-1", null, 1000)).isEqualTo("client-sid-1");
        assertThat(Sessionizer.nextSid("client-sid-1", new SessionState(), 1000)).startsWith("client-sid-1.");
    }

    @Test
    void cookielessHashRotatesDaily() {
        MemoryKeyValueStore kv = new MemoryKeyValueStore();
        CookielessHasher h = CookielessHasher.withStore(kv, "p:", "pepper");
        String a1 = h.visitorId("2026-10-03", AnalyticsTestSupport.CHROME, "93.115.10.0", "omy.md");
        String a2 = h.visitorId("2026-10-03", AnalyticsTestSupport.CHROME, "93.115.10.0", "omy.md");
        String b = h.visitorId("2026-10-04", AnalyticsTestSupport.CHROME, "93.115.10.0", "omy.md");
        String other = h.visitorId("2026-10-03", AnalyticsTestSupport.IPHONE, "93.115.10.0", "omy.md");
        assertThat(a1).isEqualTo(a2).startsWith("c-").hasSize(34);
        assertThat(b).isNotEqualTo(a1);
        assertThat(other).isNotEqualTo(a1);
        assertThat(CookielessHasher.isCookieless(a1)).isTrue();
        CookielessHasher sameSaltOtherPepper = new CookielessHasher(day -> kv.getOrSet("p:salt:" + day, "x", java.time.Duration.ofDays(1)), "other");
        assertThat(sameSaltOtherPepper.visitorId("2026-10-03", AnalyticsTestSupport.CHROME, "93.115.10.0", "omy.md")).isNotEqualTo(a1);
        CookielessHasher fixed = new CookielessHasher(day -> "s-" + day, "");
        assertThat(fixed.visitorId("2026-10-03", "ua", "1.2.3.0", "h")).isNotEqualTo(fixed.visitorId("2026-10-04", "ua", "1.2.3.0", "h"));
    }

    @Test
    void ipTruncation() {
        assertThat(IpAnonymizer.truncate("93.115.10.25")).isEqualTo("93.115.10.0");
        assertThat(IpAnonymizer.truncate("2a02:2f0e:1234:5678::1")).isEqualTo("2a02:2f0e:1234::");
        assertThat(IpAnonymizer.truncate("not-an-ip.example.com")).isNull();
        assertThat(IpAnonymizer.clientIp("10.0.0.1", "93.115.10.25, 10.0.0.1", null, true)).isEqualTo("93.115.10.25");
        assertThat(IpAnonymizer.clientIp("10.0.0.1", "93.115.10.25", null, false)).isEqualTo("10.0.0.1");
    }

    @Test
    void rateLimiterPerMinute() {
        AnalyticsTestSupport.MutableClock clock = new AnalyticsTestSupport.MutableClock(Instant.parse("2026-10-03T10:00:05Z"));
        RateLimiter rl = new RateLimiter(new MemoryKeyValueStore(clock), "t:", 3, 100, clock);
        assertThat(rl.check("1.2.3.4", 1).allowed()).isTrue();
        assertThat(rl.check("1.2.3.4", 1).allowed()).isTrue();
        assertThat(rl.check("1.2.3.4", 1).allowed()).isTrue();
        RateLimiter.Decision d = rl.check("1.2.3.4", 1);
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterSeconds()).isEqualTo(55);
        assertThat(rl.check("5.6.7.8", 1).allowed()).isTrue();
        assertThat(rl.check("9.9.9.9", 101).allowed()).isFalse();
        clock.advance(60_000L);
        assertThat(rl.check("1.2.3.4", 1).allowed()).isTrue();
    }
}
