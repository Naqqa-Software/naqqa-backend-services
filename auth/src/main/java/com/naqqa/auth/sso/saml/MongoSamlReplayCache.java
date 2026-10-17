package com.naqqa.auth.sso.saml;

import com.naqqa.sso.saml.ReplayCache;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

public class MongoSamlReplayCache implements ReplayCache {

    public static final String COLLECTION = "sso_saml_replay";

    private final MongoTemplate template;
    private final Clock clock;

    public MongoSamlReplayCache(MongoTemplate template, Clock clock) {
        this.template = template;
        this.clock = clock;
        template.indexOps(COLLECTION).ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ofMinutes(5)).named("ttl_expiresAt"));
    }

    @Override
    public boolean markIfAbsent(String key, Instant expiresAt) {
        try {
            template.insert(new Document("_id", key).append("expiresAt", Date.from(expiresAt)), COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            Query stale = new Query(Criteria.where("_id").is(key).and("expiresAt").lte(Date.from(clock.instant())));
            Document before = template.findAndModify(stale, new Update().set("expiresAt", Date.from(expiresAt)),
                    FindAndModifyOptions.options().returnNew(false), Document.class, COLLECTION);
            return before != null;
        }
    }
}
