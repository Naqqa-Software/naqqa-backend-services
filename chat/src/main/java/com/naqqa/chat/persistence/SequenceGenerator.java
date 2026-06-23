package com.naqqa.chat.persistence;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Update;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Generates monotonically increasing Long ids backed by the {@code database_sequences}
 * collection, mimicking the auto-increment behaviour previously provided by JPA/Postgres.
 */
public class SequenceGenerator {

    private final MongoOperations mongoOperations;

    public SequenceGenerator(MongoOperations mongoOperations) {
        this.mongoOperations = mongoOperations;
    }

    public long generateSequence(String seqName) {
        DatabaseSequence counter = mongoOperations.findAndModify(
                query(where("_id").is(seqName)),
                new Update().inc("seq", 1),
                FindAndModifyOptions.options().returnNew(true).upsert(true),
                DatabaseSequence.class);
        return counter != null ? counter.getSeq() : 1;
    }
}
