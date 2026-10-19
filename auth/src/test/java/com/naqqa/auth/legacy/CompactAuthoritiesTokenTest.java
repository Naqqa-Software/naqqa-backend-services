package com.naqqa.auth.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.naqqa.auth.config.CompactAuthoritiesTest;
import com.naqqa.auth.config.authorities.CompactAuthorities;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.Jwts;
import com.naqqa.auth.support.SsoTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactAuthoritiesTokenTest extends SsoTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String resign(String token, String claim, Object value) throws Exception {
        ObjectNode payload = (ObjectNode) Jwts.payload(token);
        payload.set(claim, MAPPER.valueToTree(value));
        return Jwts.sign(Jwts.header(token).toString(), payload.toString(), Jwts.testPrivateKey());
    }

    @Test
    @DisplayName("SSO-T-auth-806 legacy mode: the large set travels compact (cookie and Bearer), @PreAuthorize sees the same authorities, and a signed but malformed authz is a 401")
    void legacyCompact() throws Exception {
        List<String> all = new ArrayList<>(CompactAuthoritiesTest.osAdmin());
        all.add("profile:read");
        String email = uniqueEmail("perf-legacy");
        UserEntity user = createUser(email, passwordService.hash(PASSWORD), role("PERF_LEGACY_ADMIN", all.toArray(String[]::new)));
        Browser b = loggedIn(email);
        String token = b.cookie("access_token");
        assertTrue(("access_token=" + token).length() < 3500, String.valueOf(token.length()));
        assertTrue(Jwts.payload(token).has(CompactAuthorities.CLAIM));
        Set<String> expected = TokenService.authoritiesOf(userRepository.findById(user.getId()).orElseThrow());
        for (Browser who : List.of(b, browser().bearer(token))) {
            MvcResult me = who.get("/api/auth/test/me");
            assertEquals(200, status(me), content(me));
            Set<String> got = new TreeSet<>();
            json(me).get("authorities").forEach(n -> got.add(n.asText()));
            assertEquals(expected, got);
        }
        // Signed with the real key: the signature holds, the claim does not decode, the token is refused.
        for (Object bad : List.of("d1.AAAA", "x", 7)) {
            assertEquals(401, status(browser().bearer(resign(token, CompactAuthorities.CLAIM, bad)).get("/api/auth/test/me")));
        }
        // Control: the same re-signing with the genuine value still authenticates.
        JsonNode genuine = Jwts.payload(token).get(CompactAuthorities.CLAIM);
        assertEquals(200, status(browser().bearer(resign(token, CompactAuthorities.CLAIM, genuine.asText())).get("/api/auth/test/me")));
    }
}
