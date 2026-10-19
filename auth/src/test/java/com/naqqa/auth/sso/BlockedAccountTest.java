package com.naqqa.auth.sso;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AU-BE-24: {@code blocked} (set by imports, migrations or operators in the store) must stop an account exactly like
 * {@code enabled=false}: no sign-in on any path, no refresh, no self-service acting as the account, no impersonation.
 */
class BlockedAccountTest extends OidcTestBase {

    private void block(String email) {
        mongo.getCollection("users").updateOne(new Document("email", email), new Document("$set", new Document("blocked", true)));
    }

    @Test
    @DisplayName("SSO-T-auth-782 AU-BE-24 a blocked account is refused by password login, refresh, current-user endpoints, magic link and email OTP sign-in")
    void blockedAccountIsRefusedEverywhere() {
        String email = uniqueEmail("blocked");
        user(email);
        Browser session = loggedIn(email);
        assertEquals(202, status(browser().post(BASE + "/magic-link/request", Map.of("email", email, "redirect", "/"))));
        Matcher m = Pattern.compile("token=([^&]+)").matcher(transport.last(email).orElseThrow().vars().get("link"));
        assertTrue(m.find());
        String magic = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        Browser otp = browser();
        assertEquals(202, status(otp.post(BASE + "/otp/request", Map.of("channel", "email", "destination", email))));
        String code = transport.last(email).orElseThrow().vars().get("code");

        block(email);

        MvcResult pwd = login(browser(), email, PASSWORD);
        assertEquals(403, status(pwd), content(pwd));
        assertNotEquals(200, status(session.post(BASE + "/refresh", null)));
        assertEquals(401, status(session.get(BASE + "/mfa/factors")));
        assertNotEquals(200, status(browser().post(BASE + "/magic-link/consume", Map.of("token", magic))));
        assertNotEquals(200, status(otp.post(BASE + "/otp/verify", Map.of("channel", "email", "destination", email, "code", code))));
        int mails = transport.to(email).size();
        browser().post(BASE + "/magic-link/request", Map.of("email", email, "redirect", "/"));
        assertEquals(mails, transport.to(email).size(), "no sign-in link is mailed to a blocked account");
    }

    @Test
    @DisplayName("SSO-T-auth-783 AU-BE-24 a blocked account cannot be impersonated")
    void blockedAccountCannotBeImpersonated() {
        UserEntity support = createUser(uniqueEmail("blocked-support"), passwordService.hash(PASSWORD),
                role("BLOCKED_SUPPORT", "SSO_IMPERSONATE", "profile:read"));
        defaultTenantAdmin(support);
        String email = uniqueEmail("blocked-target");
        UserEntity target = user(email);
        block(email);
        MvcResult r = loggedIn(support.getEmail()).post(BASE + "/admin/impersonate", Map.of("userId", target.getId(), "reason", "ticket"));
        assertEquals(403, status(r), content(r));
    }
}
