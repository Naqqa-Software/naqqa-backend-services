package com.naqqa.acs.web;

import com.naqqa.acs.AcsFilter;
import com.naqqa.acs.AcsVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AcsVerifierGateTest {

    private static AcsFilter verifier() {
        return new AcsFilter(new AcsVerifier(Map.of(), "omy", null, 5000),
                List.of(AcsFilter.Rule.sensitive("POST", "/api/auth/login", "login")), System::currentTimeMillis);
    }

    @Test
    void monitorRecordsTheRefusalButLetsTheRequestThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new AcsVerifierGate(verifier(), false).doFilter(req, res, chain);
        assertNotNull(chain.getRequest());
        assertEquals(200, res.getStatus());
        assertEquals("attest", req.getAttribute(AcsVerifierGate.ATTR_REFUSED));
        assertEquals("challenge", res.getHeader("x-acs-would"));
        assertNull(res.getHeader("x-acs-challenge"));
    }

    @Test
    void enforceReturnsTheChallengeAndUnprotectedRoutesPass() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new AcsVerifierGate(verifier(), true).doFilter(new MockHttpServletRequest("POST", "/api/auth/login"), res, chain);
        assertNull(chain.getRequest());
        assertEquals(401, res.getStatus());
        assertEquals("attest", res.getHeader("x-acs-challenge"));
        MockFilterChain open = new MockFilterChain();
        new AcsVerifierGate(verifier(), true).doFilter(new MockHttpServletRequest("GET", "/api/public/menu"), new MockHttpServletResponse(), open);
        assertNotNull(open.getRequest());
    }
}
