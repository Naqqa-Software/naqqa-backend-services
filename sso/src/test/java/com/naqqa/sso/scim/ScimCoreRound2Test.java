package com.naqqa.sso.scim;

import com.naqqa.sso.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScimCoreRound2Test extends ScimTestBase {

    private List<Object> members(String gid) {
        Object m = svc.getGroup(T1, gid, null).body().get("members");
        return m == null ? List.of() : Json.asList(m);
    }

    @Test
    @DisplayName("SSO-T-scim-148 a failing deprovisioning hook keeps the SCIM user so the IdP retry runs the hook and the group cleanup")
    void hookFailureRetryable() {
        String u = mkUser(T1, "hookfail");
        String g = mkGroup(T1, "HF", u);
        int[] failures = {1};
        List<String> deleted = new java.util.ArrayList<>();
        ScimHooks failing = new ScimHooks() {
            @Override
            public void onDeleted(String tenantId, String userId) {
                if (failures[0]-- > 0) {
                    throw new IllegalStateException("downstream");
                }
                deleted.add(tenantId + "/" + userId);
            }
        };
        ScimService s = new ScimService(userStore, groupStore, failing, ScimConfig.defaults("https://sso.example.com/scim/v2"), clock);
        assertEquals(500, s.deleteUser(T1, u, null).status());
        assertTrue(userStore.find(T1, u).isPresent());
        assertEquals(204, s.deleteUser(T1, u, null).status());
        assertEquals(List.of(T1 + "/" + u), deleted);
        assertTrue(members(g).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-scim-151 a deprovisioning hook that already removed the SCIM record still answers 204 and cleans groups")
    void hookRemovesRecord() {
        String u = mkUser(T1, "selfremove");
        String g = mkGroup(T1, "SR", u);
        ScimHooks erasing = new ScimHooks() {
            @Override
            public void onDeleted(String tenantId, String userId) {
                userStore.delete(tenantId, userId);
            }
        };
        ScimService s = new ScimService(userStore, groupStore, erasing, ScimConfig.defaults("https://sso.example.com/scim/v2"), clock);
        assertEquals(204, s.deleteUser(T1, u, null).status());
        assertTrue(members(g).isEmpty());
        assertEquals(404, s.deleteUser(T1, u, null).status());
    }

    @Test
    @DisplayName("SSO-T-scim-149 a member left behind by a deleted user never blocks later group changes; new unknown members still rejected")
    void danglingMemberDoesNotWedgeGroup() {
        String a = mkUser(T1, "dang-a");
        String b = mkUser(T1, "dang-b");
        String c = mkUser(T1, "dang-c");
        String g = mkGroup(T1, "DG", a, b);
        assertTrue(userStore.delete(T1, a));
        assertEquals(200, svc.getGroup(T1, g, null).status());
        assertEquals(200, patchGroup(T1, g, "{'op':'add','path':'members','value':[{'value':'" + c + "'}]}").status());
        List<Object> m = members(g);
        assertEquals(2, m.size());
        assertTrue(m.stream().noneMatch(x -> a.equals(Json.asObject(x).get("value"))));
        assertEquals(400, patchGroup(T1, g, "{'op':'add','path':'members','value':[{'value':'ghost'}]}").status());
    }

    @Test
    @DisplayName("SSO-T-scim-150 group cleanup after user delete is best effort per group even when the store throws")
    void cleanupBestEffort() {
        String u = mkUser(T1, "be-u");
        String g1 = mkGroup(T1, "BE1", u);
        String g2 = mkGroup(T1, "BE2", u);
        int[] calls = {0};
        ScimGroupStore throwing = new ScimGroupStore() {
            @Override
            public java.util.Optional<ScimResource> findByDisplayName(String tenantId, String displayName) {
                return groupStore.findByDisplayName(tenantId, displayName);
            }

            @Override
            public java.util.Optional<ScimResource> find(String tenantId, String id) {
                return groupStore.find(tenantId, id);
            }

            @Override
            public List<ScimResource> list(String tenantId) {
                return groupStore.list(tenantId);
            }

            @Override
            public ScimResource insert(ScimResource resource) {
                return groupStore.insert(resource);
            }

            @Override
            public ScimResource update(ScimResource next, long expectedVersion) {
                if (calls[0]++ == 0) {
                    throw new IllegalStateException("db down");
                }
                return groupStore.update(next, expectedVersion);
            }

            @Override
            public boolean delete(String tenantId, String id) {
                return groupStore.delete(tenantId, id);
            }
        };
        ScimService s = new ScimService(userStore, throwing, hooks, ScimConfig.defaults("https://sso.example.com/scim/v2"), clock);
        assertEquals(204, s.deleteUser(T1, u, null).status());
        assertTrue(hooks.deleted.contains(T1 + "/" + u));
        assertEquals(0, members(g1).size() + members(g2).size());
    }
}
