package com.naqqa.auth.sso;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.SsoEnabledTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserLostUpdateTest extends SsoEnabledTestBase {

    @Test
    @DisplayName("SSO-T-auth-757 F-CONF-18 a sign-in with the old password that rehashes a legacy hash never writes it back over a password changed meanwhile and is refused")
    void rehashDoesNotRevertAPasswordChange() throws Exception {
        UserEntity user = createUser(uniqueEmail("lost-update"), new BCryptPasswordEncoder(13).encode(PASSWORD));
        String changed = passwordService.hash("N3w-Passphrase-Changed!");
        Browser b = browser();
        CompletableFuture<MvcResult> login = CompletableFuture.supplyAsync(() -> login(b, user.getEmail(), PASSWORD));
        Thread.sleep(150);
        assertFalse(login.isDone(), "the sign-in finished before the concurrent change, the race was not exercised");
        mongo.updateFirst(new Query(Criteria.where("_id").is(user.getId())), new Update().set("password", changed), UserEntity.class);
        MvcResult r = login.get(30, TimeUnit.SECONDS);
        assertEquals(401, status(r), content(r));
        String stored = userRepository.findById(user.getId()).orElseThrow().getPassword();
        assertEquals(changed, stored);
        assertTrue(passwordService.matches("N3w-Passphrase-Changed!", stored));
    }

    @Test
    @DisplayName("SSO-T-auth-758 F-CONF-18 a rehash updates only the password field: another field changed during the sign-in survives")
    void rehashIsAFieldUpdate() throws Exception {
        UserEntity user = createUser(uniqueEmail("lost-update-field"), new BCryptPasswordEncoder(13).encode(PASSWORD));
        Browser b = browser();
        CompletableFuture<MvcResult> login = CompletableFuture.supplyAsync(() -> login(b, user.getEmail(), PASSWORD));
        Thread.sleep(150);
        assertFalse(login.isDone(), "the sign-in finished before the concurrent change, the race was not exercised");
        mongo.updateFirst(new Query(Criteria.where("_id").is(user.getId())), new Update().set("fullName", "Renamed Elsewhere"), UserEntity.class);
        assertEquals(200, status(login.get(30, TimeUnit.SECONDS)));
        UserEntity after = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(after.getPassword().startsWith("$pbkdf2-sha512$"), after.getPassword());
        assertEquals("Renamed Elsewhere", after.getFullName());
    }
}
