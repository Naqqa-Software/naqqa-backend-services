package com.naqqa.auth.sso.scim;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.RefreshTokenRepository;
import com.naqqa.auth.sso.flow.SsoCredentialRevoker;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.SsoEnabledTestBase;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = "naqqa.sso.scim.account-tenants=*")
class ScimDeleteRetryTest extends SsoEnabledTestBase {

    @Autowired
    ApiTokenService apiTokens;

    @Autowired
    ScimAccountSync accountSync;

    @MockitoSpyBean
    SsoCredentialRevoker revoker;

    @MockitoSpyBean
    RefreshTokenRepository refreshTokens;

    private Browser scim() {
        return scim(newTenant());
    }

    private String newTenant() {
        role("USER", "profile:read");
        return registeredTenant("scim-retry-" + UUID.randomUUID());
    }

    private Browser scim(String tenant) {
        return browser().bearer(apiTokens.issue(tenant, ApiTokenKind.SECRET, ApiTokenEnv.TEST, "scim", List.of("scim"), List.of()).token());
    }

    private String provision(Browser scim, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        body.put("userName", email);
        body.put("emails", List.of(Map.of("value", email, "primary", true)));
        body.put("active", true);
        body.put("password", "Sc1m-Retry-Passw0rd!");
        MvcResult created = scim.post("/scim/v2/Users", body);
        assertEquals(201, status(created), content(created));
        return json(created).get("id").asText();
    }

    @Test
    @DisplayName("SSO-T-auth-661 F-CORE-19 SCIM delete whose erasure fails after the personal-data sweep keeps the SCIM user and its account mapping, so the IdP retry erases the account")
    void lateErasureFailureRetryable() {
        Browser scim = scim();
        String email = uniqueEmail("scim-retry-late");
        String id = provision(scim, email);
        UserEntity user = userRepository.findByEmail(email).orElseThrow();
        Mockito.doThrow(new IllegalStateException("legacy store unavailable")).when(refreshTokens).findByUser(ArgumentMatchers.any());
        MvcResult failed = scim.delete("/scim/v2/Users/" + id);
        Mockito.reset(refreshTokens);
        assertEquals(500, status(failed), content(failed));
        assertEquals(200, status(scim.get("/scim/v2/Users/" + id)));
        assertTrue(userRepository.findById(user.getId()).isPresent());
        MvcResult retried = scim.delete("/scim/v2/Users/" + id);
        assertEquals(204, status(retried), content(retried));
        assertTrue(userRepository.findById(user.getId()).isEmpty());
        assertEquals(0, mongo.getCollection(ScimAccountSync.COLLECTION).countDocuments(new org.bson.Document("userId", String.valueOf(user.getId()))));
    }

    @Test
    @DisplayName("SSO-T-auth-660 F-CORE-19 SCIM delete whose account erasure fails midway answers 500 and keeps the SCIM user, so the IdP retry erases the account")
    void erasureFailureRetryable() {
        Browser scim = scim();
        String email = uniqueEmail("scim-retry");
        String id = provision(scim, email);
        UserEntity user = userRepository.findByEmail(email).orElseThrow();

        Mockito.doThrow(new IllegalStateException("revocation store unavailable")).doCallRealMethod()
                .when(revoker).revoke(ArgumentMatchers.any(), ArgumentMatchers.isNull(), ArgumentMatchers.eq("account_erasure"));
        MvcResult failed = scim.delete("/scim/v2/Users/" + id);
        assertEquals(500, status(failed), content(failed));
        assertEquals(200, status(scim.get("/scim/v2/Users/" + id)));

        MvcResult retried = scim.delete("/scim/v2/Users/" + id);
        assertEquals(204, status(retried), content(retried));
        assertTrue(userRepository.findById(user.getId()).isEmpty());
        assertEquals(404, status(scim.get("/scim/v2/Users/" + id)));
    }

    @Test
    @DisplayName("SSO-T-auth-662 F-CORE-19 parallel SCIM deletes of one user erase the account once and answer only 204 or 404")
    void parallelDeletes() throws Exception {
        Browser scim = scim();
        for (int round = 0; round < 5; round++) {
            String email = uniqueEmail("scim-par-" + round);
            String id = provision(scim, email);
            UserEntity user = userRepository.findByEmail(email).orElseThrow();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return status(scim.delete("/scim/v2/Users/" + id));
                }));
            }
            go.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Integer> f : futures) {
                statuses.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            pool.shutdown();
            assertTrue(statuses.stream().allMatch(st -> st == 204 || st == 404), statuses.toString());
            assertTrue(statuses.contains(204), statuses.toString());
            assertTrue(userRepository.findById(user.getId()).isEmpty());
            assertEquals(404, status(scim.get("/scim/v2/Users/" + id)));
        }
    }

    @Test
    @DisplayName("SSO-T-auth-663 F-CORE-25 the SCIM delete hook erases the account but leaves the SCIM record being deleted to ScimService, so the DELETE answers 204 whatever the core does with a record that vanished under it")
    void deleteHookLeavesRecordToCore() {
        String tenant = newTenant();
        Browser scim = scim(tenant);
        String email = uniqueEmail("scim-hook");
        String id = provision(scim, email);
        UserEntity user = userRepository.findByEmail(email).orElseThrow();
        accountSync.onDeleted(tenant, id);
        assertTrue(userRepository.findById(user.getId()).isEmpty());
        assertEquals(1, mongo.getCollection(MongoScimUserStore.COLLECTION).countDocuments(new org.bson.Document("_id", ScimDocuments.key(tenant, id))));
        assertEquals(0, mongo.getCollection(ScimAccountSync.COLLECTION).countDocuments(new org.bson.Document("userId", String.valueOf(user.getId()))));
        MvcResult deleted = scim.delete("/scim/v2/Users/" + id);
        assertEquals(204, status(deleted), content(deleted));
        assertEquals(404, status(scim.get("/scim/v2/Users/" + id)));
    }
}
