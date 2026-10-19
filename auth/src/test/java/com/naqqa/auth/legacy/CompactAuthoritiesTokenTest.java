package com.naqqa.auth.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.naqqa.auth.config.CompactAuthoritiesTest;
import com.naqqa.auth.config.authorities.CompactAuthorities;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.config.authorities.JwtToAuthorities;
import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.Jwts;
import com.naqqa.auth.support.SsoTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static Set<String> decoded(String token) {
        Set<String> out = new TreeSet<>(CompactAuthorities.decode(Jwts.payload(token).get(CompactAuthorities.CLAIM).asText()));
        return out;
    }

    @Test
    @DisplayName("SSO-T-auth-808 AU-BE-66: authz is covered by the signature, cannot be combined with authorities, and a reduced role reaches the next token while the old one ends at its expiry as before")
    void signatureAndRevocation() throws Exception {
        List<String> all = new ArrayList<>(CompactAuthoritiesTest.osAdmin());
        all.add("profile:read");
        String roleName = "PERF_REVOKE_" + uniqueEmail("r").hashCode();
        RoleEntity role = role(roleName, all.toArray(String[]::new));
        String email = uniqueEmail("perf-revoke");
        createUser(email, passwordService.hash(PASSWORD), role);
        Browser b = loggedIn(email);
        String old = b.cookie("access_token");
        assertTrue(decoded(old).contains("profile:read"));

        // 1. Signature coverage: another authz in the payload with the original signature is refused.
        String[] parts = old.split("\\.");
        ObjectNode payload = (ObjectNode) Jwts.payload(old);
        payload.put(CompactAuthorities.CLAIM, CompactAuthorities.encode(List.of("profile:read", "SSO_ADMIN_KEYS", "zz:extra")));
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes()) + "." + parts[2];
        assertEquals(401, status(browser().bearer(forged).get("/api/auth/test/me")));
        // Removing authz and adding a plain list under the same signature is refused as well.
        ObjectNode plain = (ObjectNode) Jwts.payload(old);
        plain.remove(CompactAuthorities.CLAIM);
        plain.set(JwtToAuthorities.AUTHORITY_CLAIM_NAME, MAPPER.valueToTree(List.of("profile:read")));
        String swapped = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(plain.toString().getBytes()) + "." + parts[2];
        assertEquals(401, status(browser().bearer(swapped).get("/api/auth/test/me")));
        // 2. Format confusion: both forms in one validly signed token are refused, never merged.
        assertEquals(401, status(browser().bearer(resign(old, JwtToAuthorities.AUTHORITY_CLAIM_NAME, List.of("extra:grant")))
                .get("/api/auth/test/me")));
        // 3. Duplicates in a validly signed authz (non canonical) are refused.
        String dup = CompactAuthorities.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(rawDeflate("profile:read\nprofile:read"));
        assertEquals(401, status(browser().bearer(resign(old, CompactAuthorities.CLAIM, dup)).get("/api/auth/test/me")));

        // 4. Revocation: profile:read leaves the role; the refreshed token no longer carries it (re-read from the database).
        RoleEntity stored = roleRepository.findByName(roleName).orElseThrow();
        stored.setAuthorities(new HashSet<>(stored.getAuthorities().stream().filter(a -> !a.getName().equals("profile:read")).toList()));
        roleRepository.save(stored);
        clock.advance(Duration.ofSeconds(5));
        assertEquals(200, status(b.post(BASE + "/refresh", null)));
        String fresh = b.cookie("access_token");
        assertFalse(decoded(fresh).contains("profile:read"));
        assertEquals(403, status(browser().bearer(fresh).get("/api/auth/test/me")));
        // The old snapshot keeps working only until its own expiry, exactly like the plain list did, then 401.
        assertEquals(200, status(browser().bearer(old).get("/api/auth/test/me")));
        clock.advance(Duration.ofMinutes(16));
        assertEquals(401, status(browser().bearer(old).get("/api/auth/test/me")));
    }

    private static byte[] rawDeflate(String text) {
        java.util.zip.Deflater d = new java.util.zip.Deflater(9, true);
        d.setInput(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        d.finish();
        byte[] buf = new byte[text.length() + 64];
        int n = d.deflate(buf);
        d.end();
        return java.util.Arrays.copyOf(buf, n);
    }
}
