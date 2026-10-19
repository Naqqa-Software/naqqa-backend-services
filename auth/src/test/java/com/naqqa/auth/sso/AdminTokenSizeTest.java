package com.naqqa.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.auth.config.CompactAuthoritiesTest;
import com.naqqa.auth.config.authorities.CompactAuthorities;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.entity.authorities.SubRoleEntity;
import com.naqqa.auth.repository.SubRoleRepository;
import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.Jwts;
import com.naqqa.auth.support.SsoEnabledTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PERF-FE-1: the access_token cookie of the largest administrator fits a browser cookie, with the same authorities. */
class AdminTokenSizeTest extends SsoEnabledTestBase {

    static final int COOKIE_BUDGET = 3500;

    @Autowired
    SubRoleRepository subRoleRepository;

    private static Set<String> authorities(Browser b) {
        MvcResult r = b.get("/api/auth/test/whoami");
        assertEquals(200, status(r), content(r));
        Set<String> out = new TreeSet<>();
        json(r).get("authorities").forEach(n -> out.add(n.asText()));
        return out;
    }

    private static int cookieSize(Browser b) {
        return ("access_token=" + b.cookie("access_token")).length();
    }

    private UserEntity reload(UserEntity u) {
        return userRepository.findById(u.getId()).orElseThrow();
    }

    @Test
    @DisplayName("SSO-T-auth-804 the os ADMIN (154 authorities plus a sub-role) gets a cookie under 3.5 KB after MFA, refresh and role switch, with exactly its authorities")
    void adminCookieFits() {
        List<String> all = new ArrayList<>(CompactAuthoritiesTest.osAdmin());
        all.add("profile:read");
        RoleEntity admin = role("PERF_ADMIN_" + uniqueEmail("r").hashCode(), all.toArray(String[]::new));
        RoleEntity second = role("PERF_SECOND_" + uniqueEmail("r").hashCode(), "profile:read", "kanban:read");
        String email = uniqueEmail("perf-admin");
        UserEntity user = createUser(email, passwordService.hash(PASSWORD), admin, second);
        SubRoleEntity sub = new SubRoleEntity();
        sub.setName("PERF_SUB_" + uniqueEmail("s").hashCode());
        sub.setAuthorities(new HashSet<>(Set.of(authority("perf:only-in-sub-role"))));
        user.setSubRoles(new HashSet<>(Set.of(subRoleRepository.save(sub))));
        user = userRepository.save(user);
        defaultTenantAdmin(user);

        Totp totp = enrollTotp(loggedIn(email));
        Browser b = fullyLoggedIn(email, totp.secret());
        Set<String> expected = TokenService.authoritiesOf(reload(user));
        assertTrue(expected.size() >= 156 && expected.contains("perf:only-in-sub-role"), String.valueOf(expected.size()));
        assertTrue(cookieSize(b) < COOKIE_BUDGET, "cookie " + cookieSize(b));
        JsonNode claims = Jwts.payload(b.cookie("access_token"));
        assertFalse(claims.has("authorities"));
        assertTrue(claims.get(CompactAuthorities.CLAIM).asText().startsWith(CompactAuthorities.PREFIX));
        assertEquals("access", claims.get("token_use").asText());
        assertEquals("JWT", Jwts.header(b.cookie("access_token")).get("typ").asText());
        assertEquals(expected, authorities(b));
        assertEquals(200, status(b.get("/api/auth/test/me")));

        clock.advance(java.time.Duration.ofSeconds(5));
        MvcResult refreshed = b.post(BASE + "/refresh", null);
        assertEquals(200, status(refreshed), content(refreshed));
        assertTrue(cookieSize(b) < COOKIE_BUDGET, "cookie after refresh " + cookieSize(b));
        assertEquals(expected, authorities(b));
    }

    @Test
    @DisplayName("SSO-T-auth-805 roles under the inline limit keep the plain authorities list, byte for byte the previous claim")
    void smallRolesUnchanged() {
        RoleEntity designer = role("PERF_DESIGNER", "builder:read", "builder:create", "builder:update", "builder:delete", "builder:publish",
                "project:read", "project:create", "project:update", "project:switch", "comment:read", "comment:reply", "comment:delete",
                "kanban:read", "task:read", "task:create", "task:update", "task:assign", "time:read", "time:create", "time:update",
                "time:delete", "time:generate", "profile:read");
        String email = uniqueEmail("perf-designer");
        UserEntity user = createUser(email, passwordService.hash(PASSWORD), designer);
        Browser b = loggedIn(email);
        JsonNode claims = Jwts.payload(b.cookie("access_token"));
        assertFalse(claims.has(CompactAuthorities.CLAIM));
        List<String> listed = new ArrayList<>();
        claims.get("authorities").forEach(n -> listed.add(n.asText()));
        assertEquals(new ArrayList<>(TokenService.authoritiesOf(reload(user))), listed);
        assertEquals(new TreeSet<>(listed), authorities(b));
    }
}
