package com.naqqa.auth.sso.store;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.SsoSecrets;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.sso.util.Bytes;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditKeyRotationTest extends MongoStoreTestBase {

    static final String PEPPER = Bytes.b64url(Bytes.random(32));

    private SsoAudit audit(String key, String previous) {
        MockEnvironment env = new MockEnvironment().withProperty("NAQQA_SSO_PEPPER", PEPPER).withProperty("NAQQA_SSO_AUDIT_KEY", key);
        if (previous != null) {
            env.withProperty("NAQQA_SSO_AUDIT_PREVIOUS_KEYS", previous);
        }
        SsoProperties properties = new SsoProperties();
        return new SsoAudit(new MongoAuditStore(template), template, new SsoSecrets(properties, env), CLOCK, properties);
    }

    private static String newKey() {
        return Bytes.b64url(Bytes.random(32));
    }

    private List<Document> entries() {
        return template.find(new Query().with(Sort.by("_id")), Document.class, MongoAuditStore.COLLECTION);
    }

    @Test
    @DisplayName("SSO-T-auth-545 F-G7 audit key rotation via NAQQA_SSO_AUDIT_PREVIOUS_KEYS: entries before and after rotation verify, tampering is detected and an unknown key is rejected")
    void rotation() {
        String k1 = newKey();
        String k2 = newKey();
        String k3 = newKey();

        SsoAudit first = audit(k1, null);
        for (int i = 0; i < 3; i++) {
            assertNotNull(first.record("rotation.before", "u" + i, "u" + i, "success", Map.of("i", Integer.toString(i))));
        }
        assertTrue(first.checkpoint().isPresent());
        assertTrue(first.verify(true).ok());

        SsoAudit second = audit(k2, k1);
        for (int i = 0; i < 3; i++) {
            assertNotNull(second.record("rotation.after", "u" + i, "u" + i, "success", Map.of("i", Integer.toString(i))));
        }
        assertTrue(second.checkpoint().isPresent());
        Set<String> kids = new LinkedHashSet<>();
        for (Document d : entries()) {
            kids.add(d.getString("keyId"));
        }
        assertEquals(2, kids.size(), kids.toString());
        SsoAudit.Verification full = second.verify(true);
        assertTrue(full.ok(), String.valueOf(full.reason()));
        assertEquals(6, full.checkedTo());
        assertEquals("audit.key.rotate", template.findById(3L, Document.class, MongoAuditStore.COLLECTION).getString("action"));
        assertTrue(second.verify(false).ok());
        assertTrue(audit(k2, " " + k3 + " , " + k1 + " ").verify(true).ok());

        SsoAudit.Verification dropped = audit(k2, null).verify(true);
        assertFalse(dropped.ok());
        SsoAudit.Verification unknown = audit(k3, null).verify(true);
        assertFalse(unknown.ok());
        assertNotNull(unknown.reason());

        for (long seq : new long[]{1, 3, 5}) {
            Document original = template.findById(seq, Document.class, MongoAuditStore.COLLECTION);
            assertNotNull(original);
            template.updateFirst(new Query(Criteria.where("_id").is(seq)), new Update().set("outcome", "denied"), MongoAuditStore.COLLECTION);
            SsoAudit.Verification tampered = second.verify(true);
            assertFalse(tampered.ok());
            assertEquals(seq, tampered.badSeq());
            template.updateFirst(new Query(Criteria.where("_id").is(seq)), new Update().set("outcome", original.getString("outcome")),
                    MongoAuditStore.COLLECTION);
            assertTrue(second.verify(true).ok());
        }

        Document last = entries().get(entries().size() - 1);
        String forgedKid = "a" + "0".repeat(8);
        assertNotEquals(forgedKid, last.getString("keyId"));
        template.updateFirst(new Query(Criteria.where("_id").is(last.get("_id"))), new Update().set("keyId", forgedKid), MongoAuditStore.COLLECTION);
        assertFalse(second.verify(true).ok());
        template.updateFirst(new Query(Criteria.where("_id").is(last.get("_id"))), new Update().set("keyId", last.getString("keyId")),
                MongoAuditStore.COLLECTION);
        assertTrue(second.verify(true).ok());
    }
}
