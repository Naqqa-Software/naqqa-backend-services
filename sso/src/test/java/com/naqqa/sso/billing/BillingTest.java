package com.naqqa.sso.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class BillingTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();

    static final class MutableClock extends Clock {
        volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("SSO-T-billing-001 MAU counts distinct users once per month")
    void distinct() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        UsageMeter m = new UsageMeter(new MutableClock(Instant.parse("2026-03-10T00:00:00Z")), KEY, sink, 100);
        assertTrue(m.recordActiveUser("t1", "u1"));
        assertFalse(m.recordActiveUser("t1", "u1"));
        assertTrue(m.recordActiveUser("t1", "u2"));
        assertEquals(2, m.snapshot("t1").monthlyActiveUsers());
        assertEquals(2, sink.events().size());
    }

    @Test
    @DisplayName("SSO-T-billing-002 events carry only an HMAC of the user id")
    void privacy() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        UsageMeter m = new UsageMeter(new MutableClock(Instant.parse("2026-03-10T00:00:00Z")), KEY, sink, 100);
        m.recordActiveUser("t1", "alice@example.com");
        String json = sink.events().get(0).toJson();
        assertFalse(json.contains("alice"));
        assertEquals(64, sink.events().get(0).subject().length());
    }

    @Test
    @DisplayName("SSO-T-billing-003 month rollover resets MAU and counters")
    void rollover() {
        MutableClock c = new MutableClock(Instant.parse("2026-03-31T23:59:59Z"));
        UsageMeter m = new UsageMeter(c, KEY, e -> { }, 100);
        m.recordActiveUser("t1", "u1");
        m.recordAuthEvent("t1", "login");
        c.now = Instant.parse("2026-04-01T00:00:00Z");
        assertEquals(0, m.snapshot("t1").monthlyActiveUsers());
        assertEquals(0, m.snapshot("t1").authEvents());
        assertTrue(m.recordActiveUser("t1", "u1"));
        assertEquals("2026-04", m.snapshot("t1").month());
    }

    @Test
    @DisplayName("SSO-T-billing-004 tenant isolation: same user in two tenants, separate counts and tokens")
    void isolation() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        UsageMeter m = new UsageMeter(new MutableClock(Instant.parse("2026-03-10T00:00:00Z")), KEY, sink, 100);
        assertTrue(m.recordActiveUser("a", "u1"));
        assertTrue(m.recordActiveUser("b", "u1"));
        assertEquals(1, m.snapshot("a").monthlyActiveUsers());
        assertEquals(1, m.snapshot("b").monthlyActiveUsers());
        assertNotEquals(sink.events().get(0).subject(), sink.events().get(1).subject());
        m.recordTokenIssued("a", "access");
        assertEquals(0, m.snapshot("b").tokensIssued());
    }

    @Test
    @DisplayName("SSO-T-billing-005 MAU set is capped and reports saturation")
    void cap() {
        UsageMeter m = new UsageMeter(new MutableClock(Instant.parse("2026-03-10T00:00:00Z")), KEY, e -> { }, 3);
        for (int i = 0; i < 10; i++) {
            m.recordActiveUser("t", "u" + i);
        }
        UsageMeter.Snapshot s = m.snapshot("t");
        assertEquals(3, s.monthlyActiveUsers());
        assertTrue(s.mauSaturated());
    }

    @Test
    @DisplayName("SSO-T-billing-006 concurrent recording is exact for distinct and repeated users")
    void concurrency() throws Exception {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        UsageMeter m = new UsageMeter(new MutableClock(Instant.parse("2026-03-10T00:00:00Z")), KEY, sink, 100_000);
        ExecutorService ex = Executors.newFixedThreadPool(16);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            fs.add(ex.submit(() -> {
                for (int i = 0; i < 500; i++) {
                    m.recordActiveUser("t", "u" + i);
                    m.recordAuthEvent("t", "login");
                    m.recordAuthRequest("t");
                }
            }));
        }
        for (Future<?> f : fs) {
            f.get();
        }
        ex.shutdown();
        UsageMeter.Snapshot s = m.snapshot("t");
        assertEquals(500, s.monthlyActiveUsers());
        assertEquals(8000, s.authEvents());
        assertEquals(8000, s.authRequestsThisMinute());
        assertEquals(500, sink.events().stream().filter(e -> e.metric() == Metric.MONTHLY_ACTIVE_USERS).count());
    }

    @Test
    @DisplayName("SSO-T-billing-007 plan presets are ordered and configurable")
    void presets() {
        assertTrue(PlanQuotas.free().monthlyActiveUsers() < PlanQuotas.pro().monthlyActiveUsers());
        assertEquals(PlanQuotas.UNLIMITED, PlanQuotas.enterprise().monthlyActiveUsers());
        assertFalse(PlanQuotas.free().allows(PlanQuotas.F_SAML));
        assertTrue(PlanQuotas.pro().allows(PlanQuotas.F_SAML));
        PlanQuotas custom = PlanQuotas.free().withMonthlyActiveUsers(5).withFeatures(Set.of("x")).withSoftRatio(0.5);
        assertEquals(5, custom.limitFor(Metric.MONTHLY_ACTIVE_USERS));
        assertTrue(custom.allows("x"));
        assertThrows(IllegalArgumentException.class, () -> custom.withSoftRatio(0));
    }

    @Test
    @DisplayName("SSO-T-billing-008 soft limit warns but never blocks sign-in")
    void softNeverBlocksSignIn() {
        PlanQuotaPolicy p = new PlanQuotaPolicy(PlanQuotas.free().withMonthlyActiveUsers(10));
        assertEquals(QuotaDecision.Level.ALLOW, p.evaluate("t", Metric.MONTHLY_ACTIVE_USERS, Operation.SIGN_IN, 3).level());
        assertEquals(QuotaDecision.Level.SOFT_LIMIT, p.evaluate("t", Metric.MONTHLY_ACTIVE_USERS, Operation.SIGN_IN, 8).level());
        assertEquals(QuotaDecision.Level.SOFT_LIMIT, p.evaluate("t", Metric.MONTHLY_ACTIVE_USERS, Operation.PROVISION, 8).level());
    }

    @Test
    @DisplayName("SSO-T-billing-009 exceeded quota hard-blocks provisioning, admin and API but not sign-in")
    void hardLimit() {
        PlanQuotaPolicy p = new PlanQuotaPolicy(PlanQuotas.free().withMonthlyActiveUsers(10).withAuthRequestsPerMinute(5));
        for (Operation op : List.of(Operation.PROVISION, Operation.ADMIN, Operation.API)) {
            assertTrue(p.evaluate("t", Metric.MONTHLY_ACTIVE_USERS, op, 10).blocks(), op.name());
        }
        QuotaDecision signIn = p.evaluate("t", Metric.MONTHLY_ACTIVE_USERS, Operation.SIGN_IN, 1_000_000);
        assertFalse(signIn.blocks());
        assertEquals("soft_limit", signIn.level().wire());
        assertFalse(p.evaluate("t", Metric.AUTH_REQUESTS, Operation.SIGN_IN, 99).blocks());
        assertTrue(p.evaluate("t", Metric.AUTH_REQUESTS, Operation.API, 5).blocks());
    }

    @Test
    @DisplayName("SSO-T-billing-010 per-tenant plan assignment and unlimited enterprise")
    void assignment() {
        PlanQuotaPolicy p = new PlanQuotaPolicy(PlanQuotas.free());
        p.assign("big", PlanQuotas.enterprise());
        assertEquals("FREE", p.quotasFor("small").plan());
        assertEquals(QuotaDecision.Level.ALLOW, p.evaluate("big", Metric.OIDC_CLIENTS, Operation.ADMIN, 1_000_000).level());
        assertTrue(p.evaluate("small", Metric.SAML_SPS, Operation.ADMIN, 0).blocks());
        assertFalse(p.featureAllowed("small", PlanQuotas.F_SAML));
        assertTrue(p.featureAllowed("big", PlanQuotas.F_AUDIT_EXPORT));
    }

    @Test
    @DisplayName("SSO-T-billing-011 JSON-lines sink appends escaped one-line events")
    void jsonLines(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("sub/usage.jsonl");
        JsonLinesMeterSink s = new JsonLinesMeterSink(f);
        MutableClock c = new MutableClock(Instant.parse("2026-03-10T00:00:00Z"));
        UsageMeter m = new UsageMeter(c, KEY, s, 10);
        m.recordAuthEvent("t\"1", "log\nin");
        m.recordTokenIssued("t1", "access");
        List<String> lines = Files.readAllLines(f);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"tenant\":\"t\\\"1\""));
        assertTrue(lines.get(0).contains("log\\nin"));
        assertTrue(lines.get(1).startsWith("{\"at\":\"2026-03-10T00:00:00Z\""));
    }

    @Test
    @DisplayName("SSO-T-billing-012 requests per minute bucket resets each minute")
    void perMinute() {
        MutableClock c = new MutableClock(Instant.parse("2026-03-10T00:00:10Z"));
        UsageMeter m = new UsageMeter(c, KEY, e -> { }, 10);
        m.recordAuthRequest("t");
        m.recordAuthRequest("t");
        assertEquals(2, m.snapshot("t").authRequestsThisMinute());
        c.now = c.now.plus(Duration.ofMinutes(1));
        assertEquals(0, m.snapshot("t").authRequestsThisMinute());
    }

    @Test
    @DisplayName("SSO-T-billing-013 short HMAC key is rejected")
    void weakKey() {
        assertThrows(IllegalArgumentException.class, () -> new UsageMeter(Clock.systemUTC(), new byte[4], e -> { }, 10));
    }
}
