package com.naqqa.auth.legacy;

import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.SsoTestBase;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LegacyBlockedLoginTest extends SsoTestBase {

    @Test
    @DisplayName("SSO-T-auth-781 AU-BE-24 legacy password login refuses a blocked account and issues no tokens, and a refresh issued before the block stops working")
    void blockedAccountCannotSignIn() {
        String email = uniqueEmail("legacy-blocked");
        user(email);
        Browser before = browser();
        assertEquals(200, status(login(before, email, PASSWORD)));

        mongo.getCollection("users").updateOne(new Document("email", email), new Document("$set", new Document("blocked", true)));

        Browser b = browser();
        MvcResult r = login(b, email, PASSWORD);
        assertEquals(403, status(r), content(r));
        assertNull(b.cookie("access_token"));
        assertEquals(400, status(before.post(BASE + "/refresh", null)));
    }
}
