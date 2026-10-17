package com.naqqa.auth.sso;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.sso.flow.SsoCredentialRevoker;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.oidc.MongoOidcStore;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.sso.oidc.GrantRecord;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackRound12AuthTest extends OidcTestBase {

    @Autowired
    MongoOidcStore oidcStore;

    @Autowired
    SsoCredentialRevoker revoker;

    @Autowired
    SsoLoginFlow loginFlow;

    @Test
    @DisplayName("SSO-T-auth-683 F-A12-AUTH-4 a credential change revokes OIDC grants with the grant marker, so a refresh that read the grant before the revocation cannot save it back as live")
    void grantRevocationSurvivesConcurrentRefresh() {
        String userId = "r12-" + Bytes.randomToken(8);
        String grantId = "g-" + Bytes.randomToken(12);
        GrantRecord g = new GrantRecord(grantId, "default", "client-r12", userId, Set.of("openid", "offline_access"), null, "1", Set.of("pwd"),
                clock.instant(), null, null, null, clock.instant().plusSeconds(86400), Map.of("jti-1", clock.instant().plusSeconds(600)), false);
        oidcStore.saveGrant(g);
        GrantRecord stale = oidcStore.findGrant(grantId).orElseThrow();
        assertEquals(1, revoker.revokeOidcGrants(userId));
        oidcStore.saveGrant(stale.withAccessToken("jti-2", clock.instant().plusSeconds(600), clock.instant()));
        assertTrue(oidcStore.isRevoked("grant:" + grantId));
        assertTrue(oidcStore.isRevoked("at:jti-1"));
    }

    @Test
    @DisplayName("SSO-T-auth-684 F-A12-AUTH-5 admin unlock also clears the HOTP confirm and resync throttles")
    void unlockClearsHotpThrottles() {
        UserEntity target = user(uniqueEmail("r12-unlock"));
        String id = String.valueOf(target.getId());
        for (String key : new String[]{"hotp-confirm:" + id, "hotp-resync:" + id}) {
            for (int i = 0; i < 6; i++) {
                try {
                    loginFlow.attempt(key, randomIp(), () -> null);
                } catch (RuntimeException e) {
                    break;
                }
            }
            assertNotNull(failures(key), key);
        }
        UserEntity admin = createUser(uniqueEmail("r12-admin"), passwordService.hash(PASSWORD),
                role("R12_UNLOCK", "SSO_ADMIN_TENANTS", "SSO_ADMIN_USERS", "profile:read"));
        Browser root = loggedIn(admin.getEmail());
        assertEquals(204, status(root.post("/api/sso/admin/users/" + id + "/unlock", null)));
        assertNull(failures("hotp-confirm:" + id));
        assertNull(failures("hotp-resync:" + id));
    }

    Object failures(String account) {
        Document d = mongo.getCollection("sso_ratelimit")
                .find(new Document("_id", "lt:a:" + Bytes.hex(Hashes.sha256(Bytes.utf8(account))).substring(0, 32))).first();
        return d == null ? null : d.get("failureCount");
    }
}
