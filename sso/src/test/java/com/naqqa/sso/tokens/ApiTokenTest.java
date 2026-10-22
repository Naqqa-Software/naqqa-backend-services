package com.naqqa.sso.tokens;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.MutableClock;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTokenTest {
    private static final byte[] PEPPER = Bytes.random(32);
    private static final String IP = "203.0.113.7";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-05-01T00:00:00Z"));
    private final InMemoryApiTokenStore store = new InMemoryApiTokenStore();
    private final ApiTokenService service = new ApiTokenService(store, PEPPER, clock);

    private static String code(Runnable r) {
        return assertThrows(SsoException.class, r::run).code();
    }

    private ApiTokenService.Issued issue() {
        return service.issue("tenant-1", ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "ci", Set.of("orders:read", "orders:write"), List.of());
    }

    private static String recrc(String body) {
        return body + "_" + ApiToken.checksum(body);
    }

    @Test
    @DisplayName("SSO-T-tokens-001 issued token follows sso_<pk|sk>_<live|test>_<id>_<secret>_<crc> grammar")
    void format() {
        String t = issue().token();
        assertTrue(t.matches("sso_sk_live_[0-9A-Za-z]{16}_[0-9A-Za-z]{43}_[0-9A-Za-z]{6}"), t);
        assertEquals(ApiToken.TOKEN_LENGTH, t.length());
        String pk = service.issue("tenant-1", ApiTokenKind.PUBLISHABLE, ApiTokenEnv.TEST, "web", Set.of(), List.of()).token();
        assertTrue(pk.startsWith("sso_pk_test_"));
    }

    @Test
    @DisplayName("SSO-T-tokens-002 authenticate returns principal with server-side scopes")
    void authenticate() {
        ApiTokenService.Issued issued = issue();
        ApiTokenPrincipal p = service.authenticate(issued.token(), IP);
        assertEquals("tenant-1", p.tenantId());
        assertEquals(issued.record().id(), p.id());
        assertTrue(p.hasScope("orders:read"));
        p.requireScope("orders:write");
        assertEquals("token.scope_missing", code(() -> p.requireScope("admin")));
    }

    @Test
    @DisplayName("SSO-T-tokens-003 strict grammar rejects malformed tokens before any lookup")
    void grammar() {
        String t = issue().token();
        String[] bad = {
                t.replace("sso_", "ssx_"), t.replace("_sk_", "_xk_"), t.replace("_live_", "_prod_"),
                t + "a", t.substring(1), t.replace('_', '-'), "", null,
                t.substring(0, 20) + "!" + t.substring(21), t.substring(0, t.length() - 7) + "__" + t.substring(t.length() - 5)};
        for (String b : bad) {
            assertEquals("token.malformed", code(() -> service.authenticate(b, IP)));
        }
    }

    @Test
    @DisplayName("SSO-T-tokens-004 CRC32 base62 checksum catches typos")
    void checksum() {
        String t = issue().token();
        int pos = 30;
        char c = t.charAt(pos) == 'a' ? 'b' : 'a';
        String typo = t.substring(0, pos) + c + t.substring(pos + 1);
        assertEquals("token.checksum", code(() -> ApiToken.parse(typo)));
        assertEquals("token.malformed", code(() -> service.authenticate(typo, IP)));
        assertEquals(0, store.findById(ApiToken.parse(t).id()).orElseThrow().failedAttempts());
    }

    @Test
    @DisplayName("SSO-T-tokens-005 wrong secret with valid CRC is rejected and counted")
    void wrongSecret() {
        ApiToken real = ApiToken.parse(issue().token());
        String forged = real.withNewSecret().encode();
        assertEquals("token.invalid", code(() -> service.authenticate(forged, IP)));
        assertEquals(1, store.findById(real.id()).orElseThrow().failedAttempts());
    }

    @Test
    @DisplayName("SSO-T-tokens-006 unknown id is rejected through the dummy hash path")
    void unknownId() {
        String t = ApiToken.generate(ApiTokenKind.SECRET, ApiTokenEnv.LIVE).encode();
        assertEquals("token.invalid", code(() -> service.authenticate(t, IP)));
        assertTrue(store.findByTenant("tenant-1").isEmpty());
    }

    @Test
    @DisplayName("SSO-T-tokens-007 unknown-id timing is comparable to a real hash check")
    void dummyTiming() {
        ApiTokenService slow = new ApiTokenService(store, PEPPER, clock,
                new ApiTokenService.Config(100_000, 1000, Duration.ofMinutes(1), Duration.ZERO, 0, Duration.ofDays(1)));
        ApiToken real = ApiToken.parse(slow.issue("t", ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "x", Set.of(), List.of()).token());
        String wrong = real.withNewSecret().encode();
        String unknown = ApiToken.generate(ApiTokenKind.SECRET, ApiTokenEnv.LIVE).encode();
        long known = 0;
        long missing = 0;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            assertThrows(SsoException.class, () -> slow.authenticate(wrong, IP));
            long t1 = System.nanoTime();
            assertThrows(SsoException.class, () -> slow.authenticate(unknown, IP));
            long t2 = System.nanoTime();
            known += t1 - t0;
            missing += t2 - t1;
        }
        double ratio = (double) missing / known;
        assertTrue(ratio > 0.33 && ratio < 3.0, "ratio " + ratio);
    }

    @Test
    @DisplayName("SSO-T-tokens-008 repeated failures never lock out the correct secret")
    void lockout() {
        ApiTokenService.Issued issued = issue();
        ApiToken real = ApiToken.parse(issued.token());
        for (int i = 0; i < ApiTokenService.Config.DEFAULT.maxFailures() * 2; i++) {
            String forged = real.withNewSecret().encode();
            code(() -> service.authenticate(forged, IP));
        }
        assertEquals("tenant-1", service.authenticate(issued.token(), IP).tenantId());
        assertEquals(0, store.findById(real.id()).orElseThrow().failedAttempts());
    }

    @Test
    @DisplayName("SSO-T-tokens-009 revoked token is refused even when it sits in the verify cache")
    void revoke() {
        ApiTokenService.Issued issued = issue();
        service.authenticate(issued.token(), IP);
        service.revoke(issued.record().id());
        assertEquals("token.revoked", code(() -> service.authenticate(issued.token(), IP)));
        assertEquals("token.revoked", code(() -> service.rotate(issued.record().id(), Duration.ZERO)));
        assertEquals("token.not_found", code(() -> service.revoke("0000000000000000")));
    }

    @Test
    @DisplayName("SSO-T-tokens-010 rotation with grace keeps the old secret valid until the grace ends")
    void rotateGrace() {
        ApiTokenService.Issued first = issue();
        service.authenticate(first.token(), IP);
        ApiTokenService.Issued second = service.rotate(first.record().id(), Duration.ofHours(1));
        assertEquals(ApiToken.parse(first.token()).id(), ApiToken.parse(second.token()).id());
        service.authenticate(first.token(), IP);
        service.authenticate(second.token(), IP);
        clock.advance(Duration.ofHours(1));
        assertEquals("token.invalid", code(() -> service.authenticate(first.token(), IP)));
        service.authenticate(second.token(), IP);
    }

    @Test
    @DisplayName("SSO-T-tokens-011 rotation without grace invalidates the old secret immediately")
    void rotateNoGrace() {
        ApiTokenService.Issued first = issue();
        service.authenticate(first.token(), IP);
        ApiTokenService.Issued second = service.rotate(first.record().id(), Duration.ZERO);
        assertEquals("token.invalid", code(() -> service.authenticate(first.token(), IP)));
        service.authenticate(second.token(), IP);
        assertEquals("token.grace_invalid", code(() -> service.rotate(first.record().id(), Duration.ofDays(30))));
    }

    @Test
    @DisplayName("SSO-T-tokens-012 IPv4 CIDR allowlist allows members and denies others")
    void allowlistV4() {
        ApiTokenService.Issued issued = service.issue("tenant-1", ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "ci", Set.of(), List.of("203.0.113.0/24", "10.0.0.1"));
        service.authenticate(issued.token(), "203.0.113.200");
        service.authenticate(issued.token(), "10.0.0.1");
        service.authenticate(issued.token(), "::ffff:203.0.113.9");
        assertEquals("token.ip_denied", code(() -> service.authenticate(issued.token(), "203.0.114.1")));
        assertEquals("token.ip_denied", code(() -> service.authenticate(issued.token(), "10.0.0.2")));
        assertEquals("token.ip_denied", code(() -> service.authenticate(issued.token(), "localhost")));
        assertEquals("token.ip_denied", code(() -> service.authenticate(issued.token(), null)));
    }

    @Test
    @DisplayName("SSO-T-tokens-013 IPv6 CIDR allowlist")
    void allowlistV6() {
        ApiTokenService.Issued issued = service.issue("tenant-1", ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "ci", Set.of(), List.of("2001:db8:abcd::/48"));
        service.authenticate(issued.token(), "2001:db8:abcd:12::1");
        service.authenticate(issued.token(), "2001:DB8:ABCD:FFFF:FFFF:FFFF:FFFF:FFFF");
        assertEquals("token.ip_denied", code(() -> service.authenticate(issued.token(), "2001:db8:abce::1")));
        assertEquals("token.ip_denied", code(() -> service.authenticate(issued.token(), "203.0.113.7")));
    }

    @Test
    @DisplayName("SSO-T-tokens-014 CIDR parser is strict")
    void cidrStrict() {
        for (String bad : new String[]{"10.0.0.1/24", "10.0.0.0/33", "010.0.0.0/8", "10.0.0/8", "10.0.0.0/08", "example.com/32",
                "fe80::1%eth0/128", "1::2::3/64", ":::/0", "2001:db8::/129", "10.0.0.0/", "256.0.0.0/8", " 10.0.0.0/8", "1:2:3:4:5:6:7:8:9/128"}) {
            assertEquals(bad.equals("10.0.0.1/24") ? "cidr.host_bits" : "cidr.invalid", code(() -> Cidr.parse(bad)), bad);
        }
        assertEquals("cidr.host_bits", code(() -> Cidr.parse("2001:db8::1/64")));
        assertEquals("10.0.0.0/8", Cidr.parse("10.0.0.0/8").toString());
        assertTrue(Cidr.parse("0.0.0.0/0").contains("8.8.8.8"));
        assertTrue(Cidr.parse("10.0.0.0/7").contains("11.255.255.255"));
        assertFalse(Cidr.parse("10.0.0.0/7").contains("12.0.0.0"));
    }

    @Test
    @DisplayName("SSO-T-tokens-015 IPv6 literal forms parse without DNS")
    void ipv6Forms() {
        assertEquals(16, IpAddresses.parse("::").length);
        assertEquals(1, IpAddresses.parse("::1")[15]);
        assertEquals(4, IpAddresses.unmapV4(IpAddresses.parse("::ffff:192.0.2.1")).length);
        assertEquals((byte) 192, IpAddresses.unmapV4(IpAddresses.parse("::ffff:192.0.2.1"))[0]);
        assertEquals(16, IpAddresses.parse("1:2:3:4:5:6:7:8").length);
        assertEquals(16, IpAddresses.parse("1:2:3:4:5:6:1.2.3.4").length);
        assertEquals(16, IpAddresses.parse("2001:db8::").length);
        for (String bad : new String[]{"1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "12345::", "::g", "1:2:3:4:5:6:7:1.2.3.4", "[::1]", "::1.2.3"}) {
            assertEquals("ip.invalid", code(() -> IpAddresses.parse(bad)), bad);
        }
    }

    @Test
    @DisplayName("SSO-T-tokens-016 kind or env swapped with recomputed CRC is rejected")
    void kindSwap() {
        ApiToken real = ApiToken.parse(issue().token());
        String asPk = recrc("sso_pk_live_" + real.id() + "_" + real.secret());
        assertEquals("token.invalid", code(() -> service.authenticate(asPk, IP)));
        String asTest = recrc("sso_sk_test_" + real.id() + "_" + real.secret());
        assertEquals("token.invalid", code(() -> service.authenticate(asTest, IP)));
    }

    @Test
    @DisplayName("SSO-T-tokens-017 stored hash is PBKDF2-SHA256 over peppered HMAC and never contains the secret")
    void storedHash() {
        ApiTokenService.Issued issued = issue();
        ApiToken t = ApiToken.parse(issued.token());
        String hash = issued.record().hash();
        assertTrue(hash.startsWith("$pbkdf2-sha256$i=10000$"));
        assertFalse(hash.contains(t.secret()));
        assertFalse(issued.record().toString().contains(t.secret()));
        assertFalse(issued.toString().contains(t.secret()));
        assertFalse(t.toString().contains(t.secret()));
        ApiTokenHasher otherPepper = new ApiTokenHasher(Bytes.random(32), 10_000);
        assertFalse(otherPepper.verify(t.secret(), hash));
    }

    @Test
    @DisplayName("SSO-T-tokens-018 verify cache skips PBKDF2 within TTL and expires after it")
    void cache() {
        ApiTokenService slow = new ApiTokenService(store, PEPPER, clock,
                new ApiTokenService.Config(300_000, 10, Duration.ofMinutes(1), Duration.ofSeconds(60), 100, Duration.ofDays(1)));
        String t = slow.issue("t", ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "x", Set.of(), List.of()).token();
        long t0 = System.nanoTime();
        slow.authenticate(t, IP);
        long first = System.nanoTime() - t0;
        long t1 = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            slow.authenticate(t, IP);
        }
        long cached = System.nanoTime() - t1;
        assertTrue(cached < first, "cached " + cached + " first " + first);
        clock.advance(Duration.ofSeconds(61));
        long t2 = System.nanoTime();
        slow.authenticate(t, IP);
        long afterTtl = System.nanoTime() - t2;
        assertTrue(afterTtl > cached / 10, "after ttl " + afterTtl);
    }

    @Test
    @DisplayName("SSO-T-tokens-019 scopes are validated and updatable server-side")
    void scopes() {
        assertEquals("token.scope_invalid", code(() -> service.issue("t", ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "x", Set.of("Bad Scope"), List.of())));
        ApiTokenService.Issued issued = issue();
        service.updateScopes(issued.record().id(), Set.of("billing:read"));
        ApiTokenPrincipal p = service.authenticate(issued.token(), IP);
        assertTrue(p.hasScope("billing:read"));
        assertFalse(p.hasScope("orders:read"));
    }

    @Test
    @DisplayName("SSO-T-tokens-020 allowlist update applies on next authentication")
    void allowlistUpdate() {
        ApiTokenService.Issued issued = issue();
        service.authenticate(issued.token(), IP);
        service.updateAllowlist(issued.record().id(), List.of("192.0.2.0/24"));
        assertEquals("token.ip_denied", code(() -> service.authenticate(issued.token(), IP)));
        service.authenticate(issued.token(), "192.0.2.10");
        assertEquals("cidr.invalid", code(() -> service.updateAllowlist(issued.record().id(), List.of("nope"))));
    }

    @Test
    @DisplayName("SSO-T-tokens-021 in-memory store lists by tenant and deletes")
    void store() {
        issue();
        issue();
        service.issue("tenant-2", ApiTokenKind.PUBLISHABLE, ApiTokenEnv.TEST, "x", Set.of(), List.of());
        assertEquals(2, service.list("tenant-1").size());
        String id = service.list("tenant-2").get(0).id();
        assertTrue(store.delete(id));
        assertTrue(store.findById(id).isEmpty());
        assertTrue(store.findById(null).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-tokens-022 configuration and tenant inputs are validated")
    void config() {
        assertEquals("token.pepper_too_short", code(() -> new ApiTokenService(store, new byte[8], clock)));
        assertEquals("token.config_invalid", code(() -> new ApiTokenService.Config(10_000, 0, Duration.ZERO, Duration.ZERO, 0, Duration.ZERO)));
        assertEquals("token.config_invalid", code(() -> new ApiTokenService.Config(10_000, 5, Duration.ZERO, Duration.ofMinutes(10), 0, Duration.ZERO)));
        assertEquals("token.tenant_invalid", code(() -> service.issue(" ", ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "x", Set.of(), List.of())));
    }

    @Test
    @DisplayName("SSO-T-tokens-023 F-T1 attacker failures from IP A do not block the correct secret from IP B or IP A")
    void attackerCannotLockOutHolder() {
        ApiTokenService.Issued issued = issue();
        ApiToken real = ApiToken.parse(issued.token());
        String ipA = "198.51.100.9";
        String ipB = "203.0.113.50";
        for (int i = 0; i < 100; i++) {
            String forged = real.withNewSecret().encode();
            code(() -> service.authenticate(forged, ipA));
        }
        assertEquals("tenant-1", service.authenticate(issued.token(), ipB).tenantId());
        assertEquals("tenant-1", service.authenticate(issued.token(), ipA).tenantId());
    }

    @Test
    @DisplayName("SSO-T-tokens-024 F-T1 failures are throttled per source with backoff that expires")
    void throttlePerSource() {
        ApiToken real = ApiToken.parse(issue().token());
        int limit = ApiTokenService.Config.DEFAULT.maxFailures();
        String ipA = "198.51.100.9";
        String last = "";
        for (int i = 0; i < limit + 1; i++) {
            String forged = real.withNewSecret().encode();
            last = code(() -> service.authenticate(forged, ipA));
        }
        assertEquals("token.throttled", last);
        String forgedB = real.withNewSecret().encode();
        assertEquals("token.invalid", code(() -> service.authenticate(forgedB, "203.0.113.50")));
        clock.advance(Duration.ofMinutes(16));
        String forgedC = real.withNewSecret().encode();
        assertEquals("token.invalid", code(() -> service.authenticate(forgedC, ipA)));
    }

    @Test
    @DisplayName("SSO-T-tokens-025 F-T1 per-IP throttle spans different token ids")
    void throttlePerIp() {
        ApiTokenService.Issued one = issue();
        String ip = "198.51.100.77";
        String last = "";
        for (int i = 0; i < ApiTokenService.Config.DEFAULT.maxFailures() * 5 + 1; i++) {
            String forged = ApiToken.parse(one.token()).withNewSecret().encode();
            last = code(() -> service.authenticate(forged, ip));
        }
        assertEquals("token.throttled", last);
        String unknown = ApiToken.generate(ApiTokenKind.SECRET, ApiTokenEnv.LIVE).encode();
        assertEquals("token.throttled", code(() -> service.authenticate(unknown, ip)));
        assertEquals("tenant-1", service.authenticate(one.token(), ip).tenantId());
    }

    @Test
    @DisplayName("SSO-T-tokens-026 F-T1 global per-id counter raises an alert and does not lock")
    void alertOnly() {
        java.util.List<ApiTokenService.Alert> seen = new java.util.ArrayList<>();
        ApiTokenService svc = new ApiTokenService(store, PEPPER, clock, ApiTokenService.Config.DEFAULT, seen::add);
        ApiTokenService.Issued issued = svc.issue("t", ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "x", Set.of(), List.of());
        ApiToken real = ApiToken.parse(issued.token());
        for (int i = 0; i < ApiTokenService.Config.DEFAULT.maxFailures(); i++) {
            String forged = real.withNewSecret().encode();
            String ip = "198.51.100." + (i + 1);
            code(() -> svc.authenticate(forged, ip));
        }
        assertEquals(1, seen.size());
        assertEquals(real.id(), seen.get(0).tokenId());
        assertEquals(null, store.findById(real.id()).orElseThrow().lockedUntil());
        assertEquals("t", svc.authenticate(issued.token(), IP).tenantId());
    }
}
