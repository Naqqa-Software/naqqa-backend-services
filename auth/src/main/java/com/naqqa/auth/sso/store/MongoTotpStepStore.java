package com.naqqa.auth.sso.store;

import com.naqqa.sso.mfa.TotpStepStore;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Duration;

public class MongoTotpStepStore implements TotpStepStore {

    static final String COLLECTION = "sso_totp_steps";
    private static final Duration RETENTION = Duration.ofDays(1);

    private final MongoTemplate template;
    private final Clock clock;

    public MongoTotpStepStore(MongoTemplate template, Clock clock) {
        this.template = template;
        this.clock = clock;
    }

    @Override
    public long lastStep(String userId) {
        Document d = template.findById(userId, Document.class, COLLECTION);
        return d == null ? -1L : MongoSupport.number(d, "step");
    }

    @Override
    public boolean advance(String userId, long step) {
        Query query = new Query(Criteria.where("_id").is(userId).and("step").lt(step));
        Update update = new Update().set("step", step).set("expiresAt", MongoSupport.date(clock.instant().plus(RETENTION)));
        try {
            var result = template.upsert(query, update, COLLECTION);
            return result.getModifiedCount() > 0 || result.getUpsertedId() != null;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void clear(String userId) {
        template.remove(new Query(Criteria.where("_id").is(userId)), COLLECTION);
    }
}
