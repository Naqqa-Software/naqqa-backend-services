package com.naqqa.acs.keys;

import com.naqqa.acs.web.AcsVerifierGate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcsKeysTest {

    private static String key(int seed) {
        byte[] b = new byte[32];
        java.util.Arrays.fill(b, (byte) seed);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String keySet(String... entries) {
        StringBuilder s = new StringBuilder("{\"attestation\":[");
        for (int i = 0; i < entries.length; i += 3) {
            if (i > 0) s.append(',');
            s.append("{\"kid\":\"").append(entries[i]).append("\",\"alg\":\"Ed25519\",\"publicKey\":\"")
                    .append(entries[i + 1]).append("\",\"status\":\"").append(entries[i + 2]).append("\"}");
        }
        return s.append("]}").toString();
    }

    @Test
    void httpsRequiredExceptLoopbackInDevMode() {
        assertThrows(IllegalStateException.class, () -> AcsKeys.validateUrl("http://acs.example.com", false));
        assertThrows(IllegalStateException.class, () -> AcsKeys.validateUrl("http://acs.example.com", true));
        assertThrows(IllegalStateException.class, () -> AcsKeys.validateUrl("http://127.0.0.1:8787", false));
        assertEquals("http://127.0.0.1:8787/v1/keys", AcsKeys.validateUrl("http://127.0.0.1:8787/", true));
        assertEquals("https://acs.example.com/v1/keys", AcsKeys.validateUrl("https://acs.example.com", false));
    }

    @Test
    void onlyPinnedUsableKeysAreTrustedAndRotationKeepsThePreviousKey() {
        String a = key(1), b = key(2), evil = key(3);
        String pins = AcsKeys.fingerprint(a) + ",sha256:" + AcsKeys.fingerprint(b);
        AtomicReference<String> body = new AtomicReference<>(keySet("k1", a, "active", "kx", evil, "active"));
        List<Map<String, String>> changes = new ArrayList<>();
        AcsKeys ring = new AcsKeys("https://acs.example.com", false, pins, "", u -> body.get(), changes::add);

        assertTrue(ring.refresh());
        assertEquals(Map.of("k1", a), ring.current());

        body.set(keySet("k2", b, "active", "k1", a, "verify-only"));
        assertTrue(ring.refresh());
        assertEquals(Map.of("k2", b, "k1", a), ring.current());

        body.set(keySet("k2", b, "active", "k1", a, "retired"));
        assertTrue(ring.refresh());
        assertEquals(Map.of("k2", b), ring.current());
        assertEquals(3, changes.size());
    }

    @Test
    void failedOrUntrustedRefreshKeepsTheLastGoodSet() {
        String a = key(1);
        AtomicReference<String> body = new AtomicReference<>(keySet("k1", a, "active"));
        AcsKeys ring = new AcsKeys("https://acs.example.com", false, AcsKeys.fingerprint(a), "", u -> {
            String v = body.get();
            if (v == null) throw new IllegalStateException("down");
            return v;
        }, m -> { });
        assertTrue(ring.refresh());
        body.set(null);
        assertFalse(ring.refresh());
        assertEquals(Map.of("k1", a), ring.current());
        body.set(keySet("kx", key(9), "active"));
        assertFalse(ring.refresh());
        assertEquals(Map.of("k1", a), ring.current());
        body.set("{}");
        assertFalse(ring.refresh());
        assertEquals(Map.of("k1", a), ring.current());
    }

    @Test
    void withoutPinsNothingIsTrusted() {
        AcsKeys ring = new AcsKeys("https://acs.example.com", false, "", "k1:" + key(1),
                u -> keySet("k1", key(1), "active"), m -> { });
        assertFalse(ring.refresh());
        assertTrue(ring.current().isEmpty());
    }

    private static int status(AcsVerifierGate gate, String method, String path, MockFilterChain chain) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        gate.doFilter(new MockHttpServletRequest(method, path), res, chain);
        return res.getStatus();
    }

    @Test
    void keyOutageEnforceFailsOpenOnAuthAndClosedOnDataEndpoints() throws Exception {
        AcsVerifierGate gate = new AcsVerifierGate(null, true,
                (m, p) -> p.equals("/api/auth/login") || p.equals("/api/checkout/add") || p.equals("/api/promotions"));
        MockFilterChain login = new MockFilterChain();
        assertEquals(200, status(gate, "POST", "/api/auth/login", login));
        assertNotNull(login.getRequest());
        MockFilterChain checkout = new MockFilterChain();
        assertEquals(503, status(gate, "POST", "/api/checkout/add", checkout));
        assertNull(checkout.getRequest());
        MockFilterChain open = new MockFilterChain();
        assertEquals(200, status(gate, "GET", "/api/public/menu", open));
        assertNotNull(open.getRequest());
    }

    @Test
    void keyOutageInMonitorOnlyRecords() throws Exception {
        AcsVerifierGate gate = new AcsVerifierGate(null, false, (m, p) -> true);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/checkout/add");
        MockFilterChain chain = new MockFilterChain();
        gate.doFilter(req, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
        assertEquals("keys-unavailable", req.getAttribute(AcsVerifierGate.ATTR_REFUSED));
    }
}
