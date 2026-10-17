package com.naqqa.sso.scim;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.json.Json;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;

abstract class ScimTestBase {
    static final String T1 = "tenant-a";
    static final String T2 = "tenant-b";

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class Recorder implements ScimHooks {
        final List<String> deactivated = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        final List<String> passwords = new ArrayList<>();
        boolean rejectPasswords;

        @Override
        public void validatePassword(String tenantId, String password) {
            if (rejectPasswords) {
                throw new SsoException("password.too_weak");
            }
        }

        @Override
        public void onPasswordSet(String tenantId, String userId, String password) {
            passwords.add(tenantId + "/" + userId + "/" + password);
        }

        @Override
        public void onDeactivated(String tenantId, String userId) {
            deactivated.add(tenantId + "/" + userId);
        }

        @Override
        public void onDeleted(String tenantId, String userId) {
            deleted.add(tenantId + "/" + userId);
        }
    }

    MutableClock clock;
    Recorder hooks;
    InMemoryScimUserStore userStore;
    InMemoryScimGroupStore groupStore;
    ScimService svc;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        hooks = new Recorder();
        userStore = new InMemoryScimUserStore();
        groupStore = new InMemoryScimGroupStore();
        svc = new ScimService(userStore, groupStore, hooks, ScimConfig.defaults("https://sso.example.com/scim/v2"), clock);
    }

    static Map<String, Object> j(String json) {
        return Json.parseObject(json.replace("\"", "\\\"").replace('\'', '"'));
    }

    static String userJson(String userName) {
        return "{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'" + userName + "'}";
    }

    String mkUser(String tenant, String userName) {
        ScimResponse r = svc.createUser(tenant, j(userJson(userName)));
        if (r.status() != 201) {
            throw new IllegalStateException("create failed " + r.body());
        }
        return (String) r.body().get("id");
    }

    String mkUser(String tenant, String json, int expect) {
        ScimResponse r = svc.createUser(tenant, j(json));
        if (r.status() != expect) {
            throw new IllegalStateException("unexpected " + r.status() + " " + r.body());
        }
        return (String) r.body().get("id");
    }

    String mkGroup(String tenant, String name, String... memberIds) {
        StringBuilder sb = new StringBuilder("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:Group'],'displayName':'")
                .append(name).append("','members':[");
        for (int i = 0; i < memberIds.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{'value':'").append(memberIds[i]).append("'}");
        }
        sb.append("]}");
        ScimResponse r = svc.createGroup(tenant, j(sb.toString()));
        if (r.status() != 201) {
            throw new IllegalStateException("group create failed " + r.body());
        }
        return (String) r.body().get("id");
    }

    static String patchJson(String ops) {
        return "{'schemas':['urn:ietf:params:scim:api:messages:2.0:PatchOp'],'Operations':[" + ops + "]}";
    }

    ScimResponse patchUser(String tenant, String id, String ops) {
        return svc.patchUser(tenant, id, j(patchJson(ops)), null);
    }

    ScimResponse patchGroup(String tenant, String id, String ops) {
        return svc.patchGroup(tenant, id, j(patchJson(ops)), null);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> resources(ScimResponse r) {
        return (List<Map<String, Object>>) r.body().get("Resources");
    }

    static List<String> userNames(ScimResponse r) {
        return resources(r).stream().map(m -> (String) m.get("userName")).toList();
    }

    ScimResponse listFilter(String filter) {
        return svc.listUsers(T1, ListQuery.filter(filter));
    }

    static String scimType(ScimResponse r) {
        return (String) r.body().get("scimType");
    }
}
