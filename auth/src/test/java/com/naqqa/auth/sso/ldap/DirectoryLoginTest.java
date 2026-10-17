package com.naqqa.auth.sso.ldap;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryLoginTest extends OidcTestBase {

    static final String STAFF = "cn=staff,ou=groups,dc=corp";
    static final String SUPPORT = "cn=support,ou=groups,dc=corp";

    private MvcResult corpLogin(Browser b, String email, String password) {
        b.clientId("corp-app");
        return login(b, email, password);
    }

    private Set<String> roles(String email) {
        return userRepository.findByEmail(email).orElseThrow().getRoles().stream().map(RoleEntity::getName).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("SSO-T-auth-540 LDAP password login provisions the user just in time with roles from directory groups")
    void jitProvisioning() {
        role("USER", "profile:read");
        role("SUPPORT", "profile:read");
        String email = uniqueEmail("ldap-jit");
        String uid = email.substring(0, email.indexOf('@'));
        DIRECTORY.add(email, "dir-pass", "uid=" + uid + ",ou=people,dc=corp",
                Map.of("mail", List.of(email), "displayName", List.of("Dir User"), "memberOf", List.of(STAFF)));
        Browser b = browser();
        MvcResult r = corpLogin(b, email, "dir-pass");
        assertEquals(200, status(r), content(r));
        assertEquals("ok", json(r).get("status").asText(), content(r));
        UserEntity user = userRepository.findByEmail(email).orElseThrow();
        assertEquals("Dir User", user.getFullName());
        assertEquals(Set.of("USER"), roles(email));
        assertEquals(null, user.getPassword());
        assertEquals(1, mongo.getCollection(DirectoryLogin.COLLECTION).countDocuments(new org.bson.Document("userId", String.valueOf(user.getId()))));
        assertEquals(401, status(corpLogin(browser(), email, "wrong")));
        assertEquals(401, status(login(browser(), email, "dir-pass")));

        DIRECTORY.add(email, "dir-pass", "uid=" + uid + ",ou=people,dc=corp",
                Map.of("mail", List.of(email), "displayName", List.of("Dir User"), "memberOf", List.of(SUPPORT)));
        MvcResult again = corpLogin(b, email, "dir-pass");
        assertEquals(200, status(again), content(again));
        assertEquals(Set.of("SUPPORT"), roles(email));
        assertEquals(user.getId(), userRepository.findByEmail(email).orElseThrow().getId());
    }

    @Test
    @DisplayName("SSO-T-auth-541 local accounts outside the directory keep their local password on a directory client")
    void localFallback() {
        String email = uniqueEmail("ldap-local");
        user(email);
        assertEquals(200, status(corpLogin(browser(), email, PASSWORD)));
        assertEquals(401, status(corpLogin(browser(), email, "nope")));
        String unknown = uniqueEmail("ldap-unknown");
        MvcResult r = corpLogin(browser(), unknown, "whatever");
        assertEquals(401, status(r));
        assertTrue(userRepository.findByEmail(unknown).isEmpty());
    }
}
