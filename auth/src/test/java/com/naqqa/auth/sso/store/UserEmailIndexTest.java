package com.naqqa.auth.sso.store;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserEmailIndexTest extends MongoStoreTestBase {

    @Test
    @DisplayName("SSO-T-auth-620 F-A6-AUTH-10 several nodes starting together on a users collection with case duplicates: none fails, all report the same groups, no index; once resolved, concurrent startups create the index once")
    void concurrentStartupWithCaseDuplicates() throws Exception {
        MongoCollection<Document> users = template.getCollection("users");
        users.dropIndex(UserEmailIndex.NAME);
        users.insertMany(List.of(new Document("_id", 11L).append("email", "Case.Dup@Example.test"),
                new Document("_id", 12L).append("email", "case.dup@example.test"),
                new Document("_id", 13L).append("email", "CASE.DUP@EXAMPLE.TEST"),
                new Document("_id", 14L).append("email", "Other@example.test"),
                new Document("_id", 15L).append("email", "other@example.test"),
                new Document("_id", 16L).append("email", "solo@example.test"),
                new Document("_id", 17L)));

        List<Boolean> started = race(6, () -> {
            SsoMongoIndexes.ensure(template);
            return true;
        });
        assertEquals(6, started.size());
        List<UserEmailIndex.Report> reports = race(6, () -> UserEmailIndex.migrate(template));
        for (UserEmailIndex.Report r : reports) {
            assertFalse(r.indexPresent());
            assertEquals(2, r.groups());
            assertEquals(5, r.accounts());
        }
        assertFalse(UserEmailIndex.present(template));
        assertEquals(List.of(new UserEmailIndex.Group(11L, List.of(12L, 13L)), new UserEmailIndex.Group(14L, List.of(15L))),
                UserEmailIndex.conflicts(template));
        MongoCollection<Document> markers = template.getCollection(UserEmailIndex.CONFLICTS);
        assertEquals(3, markers.countDocuments());
        assertEquals(11L, UserEmailIndex.canonicalOf(template, 13L));
        assertEquals(null, UserEmailIndex.canonicalOf(template, 11L));

        users.updateOne(new Document("_id", 12L), new Document("$set", new Document("email", "moved12@example.test")));
        users.updateOne(new Document("_id", 13L), new Document("$set", new Document("email", "moved13@example.test")));
        assertEquals(1, race(6, () -> UserEmailIndex.migrate(template)).stream().mapToInt(UserEmailIndex.Report::groups).max().orElse(-1));
        assertEquals(1, markers.countDocuments());

        users.updateOne(new Document("_id", 15L), new Document("$set", new Document("email", "moved15@example.test")));
        List<Boolean> again = race(6, () -> {
            SsoMongoIndexes.ensure(template);
            return UserEmailIndex.present(template);
        });
        assertTrue(again.stream().allMatch(Boolean::booleanValue));
        assertEquals(0, markers.countDocuments());
        assertThrows(MongoWriteException.class, () -> users.insertOne(new Document("_id", 18L).append("email", "SOLO@example.test")));
    }
}
