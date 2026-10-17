package com.naqqa.auth.sso.store;

import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;

public class MongoSecretBoxCounterStore implements SecretBoxCounterStore {

    static final String COLLECTION = "sso_secretbox_counters";

    private final MongoTemplate template;
    private final Clock clock;

    public MongoSecretBoxCounterStore(MongoTemplate template, Clock clock) {
        this.template = template;
        this.clock = clock;
    }

    @Override
    public long reserve(String kid, long amount) {
        if (amount < 1) {
            throw new IllegalArgumentException("amount");
        }
        for (int i = 0; ; i++) {
            try {
                Document d = template.findAndModify(new Query(Criteria.where("_id").is(kid)),
                        new Update().inc("allocated", amount).setOnInsert("createdAt", MongoSupport.date(clock.instant())),
                        FindAndModifyOptions.options().upsert(true).returnNew(true), Document.class, COLLECTION);
                return d.get("allocated", Number.class).longValue();
            } catch (DuplicateKeyException e) {
                if (i >= 5) {
                    throw e;
                }
            }
        }
    }

    @Override
    public void recordUsed(String kid, long used) {
        template.upsert(new Query(Criteria.where("_id").is(kid)),
                new Update().max("used", used).set("updatedAt", MongoSupport.date(clock.instant())), COLLECTION);
    }
}
