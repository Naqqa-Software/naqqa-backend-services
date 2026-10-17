package com.naqqa.auth.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ActiveProfiles("sso")
public abstract class SsoEnabledTestBase extends SsoTestBase {

    public record Totp(String secret, List<String> recoveryCodes) {
    }

    protected String registeredTenant(String id) {
        Date now = new Date();
        mongo.getCollection("sso_tenants").replaceOne(new Document("_id", id), new Document("_id", id)
                .append("name", id).append("plan", "ENTERPRISE").append("status", "active").append("admins", List.of())
                .append("createdAt", now).append("updatedAt", now), new ReplaceOptions().upsert(true));
        return id;
    }

    protected Totp enrollTotp(Browser b) {
        MvcResult r = b.post(BASE + "/mfa/totp/enroll", null);
        assertEquals(200, status(r), content(r));
        JsonNode e = json(r);
        String secret = e.get("secret").asText();
        MvcResult c = b.post(BASE + "/mfa/totp/confirm", Map.of("enrollmentId", e.get("enrollmentId").asText(), "code", totpCode(secret)));
        assertEquals(200, status(c), content(c));
        nextTotpStep();
        List<String> codes = new java.util.ArrayList<>();
        json(c).get("recoveryCodes").forEach(n -> codes.add(n.asText()));
        return new Totp(secret, codes);
    }

    protected Browser fullyLoggedIn(String email, String secret) {
        Browser b = browser();
        MvcResult r = login(b, email, PASSWORD);
        JsonNode body = json(r);
        if ("mfa_required".equals(body.path("status").asText())) {
            MvcResult v = b.post(BASE + "/mfa/verify", Map.of("mfaToken", body.get("mfaToken").asText(), "method", "totp",
                    "code", totpCode(secret)));
            assertEquals(200, status(v), content(v));
            nextTotpStep();
        } else {
            assertEquals(200, status(r), content(r));
        }
        return b;
    }
}
