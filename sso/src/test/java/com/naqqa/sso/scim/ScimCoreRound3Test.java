package com.naqqa.sso.scim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScimCoreRound3Test extends ScimTestBase {

    private ScimService failingOnce(List<String> calls) {
        int[] failures = {1};
        ScimHooks hooks = new ScimHooks() {
            @Override
            public void onDeactivated(String tenantId, String userId) {
                calls.add(tenantId + "/" + userId);
                if (failures[0]-- > 0) {
                    throw new IllegalStateException("downstream");
                }
            }
        };
        return new ScimService(userStore, groupStore, hooks, ScimConfig.defaults("https://sso.example.com/scim/v2"), clock);
    }

    @Test
    @DisplayName("SSO-T-scim-152 F-CORE-18 a failing deactivation hook on PATCH active=false keeps the user active so the IdP retry runs the hook")
    void patchDeactivationRetryable() {
        String u = mkUser(T1, "deact-patch");
        List<String> calls = new ArrayList<>();
        ScimService s = failingOnce(calls);
        String ops = "{'op':'replace','path':'active','value':false}";
        assertEquals(500, s.patchUser(T1, u, j(patchJson(ops)), null).status());
        assertTrue(!Boolean.FALSE.equals(userStore.find(T1, u).orElseThrow().attributes().get("active")));
        assertEquals(200, s.patchUser(T1, u, j(patchJson(ops)), null).status());
        assertEquals(List.of(T1 + "/" + u, T1 + "/" + u), calls);
        assertEquals(Boolean.FALSE, userStore.find(T1, u).orElseThrow().attributes().get("active"));
    }

    @Test
    @DisplayName("SSO-T-scim-153 F-CORE-18 a failing deactivation hook on PUT active=false keeps the user active so the IdP retry runs the hook")
    void putDeactivationRetryable() {
        String u = mkUser(T1, "deact-put");
        List<String> calls = new ArrayList<>();
        ScimService s = failingOnce(calls);
        String body = "{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'deact-put','active':false}";
        assertEquals(500, s.replaceUser(T1, u, j(body), null).status());
        assertEquals(200, s.replaceUser(T1, u, j(body), null).status());
        assertEquals(2, calls.size());
        assertEquals(Boolean.FALSE, userStore.find(T1, u).orElseThrow().attributes().get("active"));
        assertEquals(200, s.replaceUser(T1, u, j(body), null).status());
        assertEquals(2, calls.size());
    }

    @Test
    @DisplayName("SSO-T-scim-154 F-CORE-28 a failing password hook on POST /Users rolls the new user back, so the IdP retry creates it and sets the password")
    void createPasswordHookRetryable() {
        List<String> calls = new ArrayList<>();
        int[] failures = {1};
        ScimHooks hooks = new ScimHooks() {
            @Override
            public void onPasswordSet(String tenantId, String userId, String password) {
                calls.add("password");
                if (failures[0]-- > 0) {
                    throw new IllegalStateException("downstream");
                }
            }

            @Override
            public void onDeleted(String tenantId, String userId) {
                calls.add("deleted");
            }
        };
        ScimService s = new ScimService(userStore, groupStore, hooks, ScimConfig.defaults("https://sso.example.com/scim/v2"), clock);
        String body = "{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'pw-create','password':'Sc1m-Create-Passw0rd!'}";
        assertEquals(500, s.createUser(T1, j(body)).status());
        assertTrue(userStore.findByUserName(T1, "pw-create").isEmpty());
        assertEquals(201, s.createUser(T1, j(body)).status());
        assertEquals(List.of("password", "deleted", "password"), calls);
        assertTrue(userStore.findByUserName(T1, "pw-create").isPresent());
    }
}
