package com.naqqa.auth.sso.store;

import com.naqqa.sso.mfa.RecoverySet;
import com.naqqa.sso.mfa.RecoveryStore;
import org.bson.Document;
import org.bson.types.Binary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MongoRecoveryStore implements RecoveryStore {

    static final String COLLECTION = "sso_recovery_sets";

    private final MongoTemplate template;

    public MongoRecoveryStore(MongoTemplate template) {
        this.template = template;
    }

    @Override
    public Optional<RecoverySet> find(String userId) {
        Document d = template.findById(userId, Document.class, COLLECTION);
        if (d == null) {
            return Optional.empty();
        }
        List<RecoverySet.Entry> entries = new ArrayList<>();
        for (Document e : d.getList("entries", Document.class, List.of())) {
            entries.add(new RecoverySet.Entry(MongoSupport.bytes(e.get("salt")), MongoSupport.bytes(e.get("hash")), MongoSupport.flag(e, "used"), e.get("lookupId") == null ? null : MongoSupport.bytes(e.get("lookupId"))));
        }
        return Optional.of(new RecoverySet(userId, entries, MongoSupport.integer(d, "failedAttempts"), MongoSupport.instant(d.getDate("lastFailure"))));
    }

    @Override
    public void save(RecoverySet set) {
        List<Document> entries = new ArrayList<>();
        for (RecoverySet.Entry e : set.entries()) {
            entries.add(new Document("salt", e.salt()).append("hash", e.hash()).append("used", e.used()).append("lookupId", e.lookupId()));
        }
        Document doc = new Document("_id", set.userId())
                .append("entries", entries)
                .append("failedAttempts", set.failedAttempts())
                .append("lastFailure", MongoSupport.date(set.lastFailure()));
        template.save(doc, COLLECTION);
    }

    @Override
    public boolean markUsed(String userId, byte[] hash) {
        Query query = new Query(Criteria.where("_id").is(userId)
                .and("entries").elemMatch(Criteria.where("hash").is(new Binary(hash)).and("used").ne(true)));
        Update update = new Update().set("entries.$.used", true).set("failedAttempts", 0).set("lastFailure", null);
        return template.updateFirst(query, update, COLLECTION).getModifiedCount() > 0;
    }

    @Override
    public void recordFailure(String userId, int failedAttempts, Instant at) {
        template.updateFirst(new Query(Criteria.where("_id").is(userId)),
                new Update().set("failedAttempts", failedAttempts).set("lastFailure", MongoSupport.date(at)), COLLECTION);
    }

    @Override
    public void delete(String userId) {
        template.remove(new Query(Criteria.where("_id").is(userId)), COLLECTION);
    }
}
