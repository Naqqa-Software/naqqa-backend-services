package com.naqqa.sso.risk;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.p2support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RiskEngineTest {
    private final TestClock clock = TestClock.at("2026-05-01T14:00:00Z");
    private final RiskEngine engine = new RiskEngine(clock);
    private static final GeoPoint BUCHAREST = new GeoPoint(44.4268, 26.1025);
    private static final GeoPoint NEW_YORK = new GeoPoint(40.7128, -74.0060);
    private static final GeoPoint CLUJ = new GeoPoint(46.7712, 23.6236);

    private RiskProfile known() {
        return new RiskProfile(Set.of("dev1"), Set.of(RiskEngine.networkOf("203.0.113.5")), Set.of("AS1234"), BUCHAREST,
                clock.instant().minus(Duration.ofHours(5)), Set.of(8, 9, 10, 11, 12, 13, 14, 15, 16, 17), ZoneId.of("UTC"));
    }

    private RiskContext ctx(String device, String ip, String asn, GeoPoint geo, boolean tor, int failures) {
        return new RiskContext(device, ip, asn, geo, tor, failures);
    }

    @Test
    @DisplayName("SSO-T-risk-001 known device, network, asn and hour scores zero and is allowed")
    void baseline() {
        RiskDecision d = engine.evaluate(ctx("dev1", "203.0.113.77", "AS1234", CLUJ, false, 0), known());
        assertEquals(RiskDecision.Action.ALLOW, d.action());
        assertEquals(0, d.score());
        assertTrue(d.reasons().isEmpty());
    }

    @Test
    @DisplayName("SSO-T-risk-002 new device alone triggers step-up with reason")
    void newDevice() {
        RiskDecision d = engine.evaluate(ctx("devX", "203.0.113.77", "AS1234", null, false, 0), known());
        assertEquals(25, d.score());
        assertEquals(RiskDecision.Action.ALLOW, d.action());
        assertEquals(java.util.List.of("new_device"), d.reasons());
    }

    @Test
    @DisplayName("SSO-T-risk-003 new device plus new network crosses the step-up threshold")
    void newDeviceAndNetwork() {
        RiskDecision d = engine.evaluate(ctx("devX", "198.51.100.9", "AS1234", null, false, 0), known());
        assertEquals(RiskDecision.Action.STEP_UP, d.action());
        assertTrue(d.reasons().containsAll(java.util.List.of("new_device", "new_network")));
    }

    @Test
    @DisplayName("SSO-T-risk-004 IPv4 networks compare on /24")
    void ipv4Prefix() {
        assertEquals(RiskEngine.networkOf("203.0.113.1"), RiskEngine.networkOf("203.0.113.250"));
        assertNotEquals(RiskEngine.networkOf("203.0.113.1"), RiskEngine.networkOf("203.0.114.1"));
    }

    @Test
    @DisplayName("SSO-T-risk-005 IPv6 networks compare on /48")
    void ipv6Prefix() {
        assertEquals(RiskEngine.networkOf("2001:db8:abcd:1::1"), RiskEngine.networkOf("2001:db8:abcd:ffff::2"));
        assertNotEquals(RiskEngine.networkOf("2001:db8:abcd:1::1"), RiskEngine.networkOf("2001:db8:abce:1::1"));
    }

    @Test
    @DisplayName("SSO-T-risk-006 invalid and hostname-like IPs never trigger DNS and map to invalid")
    void invalidIp() {
        assertEquals("invalid", RiskEngine.networkOf("example.com"));
        assertEquals("invalid", RiskEngine.networkOf(""));
        assertEquals("invalid", RiskEngine.networkOf(null));
        assertEquals("invalid", RiskEngine.networkOf("999.1.1.1.1.1"));
    }

    @Test
    @DisplayName("SSO-T-risk-007 asn change adds score only when asns are known")
    void asnChange() {
        RiskDecision d = engine.evaluate(ctx("dev1", "203.0.113.5", "AS9999", null, false, 0), known());
        assertTrue(d.reasons().contains("asn_change"));
        RiskProfile noAsn = new RiskProfile(Set.of("dev1"), Set.of(RiskEngine.networkOf("203.0.113.5")), Set.of(), null, null, Set.of(), ZoneId.of("UTC"));
        assertFalse(engine.evaluate(ctx("dev1", "203.0.113.5", "AS9999", null, false, 0), noAsn).reasons().contains("asn_change"));
    }

    @Test
    @DisplayName("SSO-T-risk-008 impossible travel Bucharest to New York in 5 hours")
    void impossibleTravel() {
        RiskDecision d = engine.evaluate(ctx("dev1", "203.0.113.5", "AS1234", NEW_YORK, false, 0), known());
        assertTrue(d.reasons().contains("impossible_travel"));
        assertEquals(RiskDecision.Action.STEP_UP, d.action());
    }

    @Test
    @DisplayName("SSO-T-risk-009 plausible travel Bucharest to Cluj in 5 hours is fine")
    void plausibleTravel() {
        assertFalse(engine.evaluate(ctx("dev1", "203.0.113.5", "AS1234", CLUJ, false, 0), known()).reasons().contains("impossible_travel"));
    }

    @Test
    @DisplayName("SSO-T-risk-010 long haul after enough elapsed time is plausible")
    void longHaulOk() {
        RiskProfile p = new RiskProfile(Set.of("dev1"), Set.of(RiskEngine.networkOf("203.0.113.5")), Set.of("AS1234"), BUCHAREST,
                clock.instant().minus(Duration.ofHours(20)), Set.of(), ZoneId.of("UTC"));
        assertFalse(engine.evaluate(ctx("dev1", "203.0.113.5", "AS1234", NEW_YORK, false, 0), p).reasons().contains("impossible_travel"));
    }

    @Test
    @DisplayName("SSO-T-risk-011 same-instant login from far away is impossible travel")
    void zeroElapsed() {
        RiskProfile p = new RiskProfile(Set.of("dev1"), Set.of(RiskEngine.networkOf("203.0.113.5")), Set.of(), BUCHAREST, clock.instant(), Set.of(), ZoneId.of("UTC"));
        assertTrue(engine.evaluate(ctx("dev1", "203.0.113.5", null, NEW_YORK, false, 0), p).reasons().contains("impossible_travel"));
    }

    @Test
    @DisplayName("SSO-T-risk-012 nearby locations under the minimum distance never count")
    void minDistance() {
        RiskProfile p = new RiskProfile(Set.of("dev1"), Set.of(RiskEngine.networkOf("203.0.113.5")), Set.of(), BUCHAREST, clock.instant(), Set.of(), ZoneId.of("UTC"));
        assertFalse(engine.evaluate(ctx("dev1", "203.0.113.5", null, new GeoPoint(44.43, 26.11), false, 0), p).reasons().contains("impossible_travel"));
    }

    @Test
    @DisplayName("SSO-T-risk-013 haversine distance sanity")
    void haversine() {
        double km = BUCHAREST.distanceKm(NEW_YORK);
        assertTrue(km > 7600 && km < 7700, "km=" + km);
        assertEquals(0.0, BUCHAREST.distanceKm(BUCHAREST), 1e-9);
    }

    @Test
    @DisplayName("SSO-T-risk-014 failure velocity tiers")
    void failureVelocity() {
        assertFalse(engine.evaluate(ctx("dev1", "203.0.113.5", "AS1234", null, false, 2), known()).reasons().contains("failure_velocity"));
        assertEquals(15, engine.evaluate(ctx("dev1", "203.0.113.5", "AS1234", null, false, 3), known()).score());
        assertEquals(30, engine.evaluate(ctx("dev1", "203.0.113.5", "AS1234", null, false, 5), known()).score());
        assertEquals(RiskDecision.Action.STEP_UP, engine.evaluate(ctx("dev1", "203.0.113.5", "AS1234", null, false, 5), known()).action());
    }

    @Test
    @DisplayName("SSO-T-risk-015 TOR or proxy flag adds score")
    void tor() {
        RiskDecision d = engine.evaluate(ctx("dev1", "203.0.113.5", "AS1234", null, true, 0), known());
        assertEquals(30, d.score());
        assertEquals(RiskDecision.Action.STEP_UP, d.action());
        assertTrue(d.reasons().contains("tor_or_proxy"));
    }

    @Test
    @DisplayName("SSO-T-risk-016 time of day anomaly uses the profile zone")
    void timeOfDay() {
        clock.set(Instant.parse("2026-05-01T03:00:00Z"));
        assertTrue(engine.evaluate(ctx("dev1", "203.0.113.5", "AS1234", null, false, 0), known()).reasons().contains("unusual_hour"));
        RiskProfile tokyo = new RiskProfile(Set.of("dev1"), Set.of(RiskEngine.networkOf("203.0.113.5")), Set.of("AS1234"), null, null,
                Set.of(12), ZoneId.of("Asia/Tokyo"));
        assertFalse(engine.evaluate(ctx("dev1", "203.0.113.5", "AS1234", null, false, 0), tokyo).reasons().contains("unusual_hour"));
    }

    @Test
    @DisplayName("SSO-T-risk-017 stacked signals reach DENY")
    void deny() {
        RiskDecision d = engine.evaluate(ctx("devX", "198.51.100.9", "AS9999", NEW_YORK, true, 5), known());
        assertEquals(RiskDecision.Action.DENY, d.action());
        assertTrue(d.score() >= 80);
        assertTrue(d.reasons().size() >= 5);
    }

    @Test
    @DisplayName("SSO-T-risk-018 thresholds are configurable")
    void configurable() {
        RiskConfig strict = new RiskConfig(10, 40, 25, 20, 15, 50, 30, 15, 30, 10, 900.0, 100.0);
        assertEquals(RiskDecision.Action.STEP_UP, new RiskEngine(clock, strict).evaluate(ctx("devX", "203.0.113.5", "AS1234", null, false, 0), known()).action());
        assertThrows(SsoException.class, () -> new RiskConfig(50, 40, 1, 1, 1, 1, 1, 1, 1, 1, 900.0, 100.0));
        assertThrows(SsoException.class, () -> new RiskConfig(0, 40, 1, 1, 1, 1, 1, 1, 1, 1, 900.0, 100.0));
    }

    @Test
    @DisplayName("SSO-T-risk-019 first login with an empty profile is step-up not deny")
    void firstLogin() {
        RiskDecision d = engine.evaluate(ctx("dev1", "203.0.113.5", "AS1234", CLUJ, false, 0), RiskProfile.empty());
        assertEquals(RiskDecision.Action.STEP_UP, d.action());
    }

    @Test
    @DisplayName("SSO-T-risk-020 missing device id counts as new device")
    void nullDevice() {
        assertTrue(engine.evaluate(ctx(null, "203.0.113.5", "AS1234", null, false, 0), known()).reasons().contains("new_device"));
    }
}
