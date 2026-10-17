package com.naqqa.auth.sso.saml;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.sp.SamlRequestStore;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

public class MongoSamlRequestStore implements SamlRequestStore {

    public static final String COLLECTION = "sso_saml_requests";

    private final MongoTemplate template;
    private final Clock clock;

    public MongoSamlRequestStore(MongoTemplate template, Clock clock) {
        this.template = template;
        this.clock = clock;
        template.indexOps(COLLECTION).ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ofMinutes(5)).named("ttl_expiresAt"));
    }

    @Override
    public void save(String requestId, Instant expiresAt) {
        try {
            template.insert(new Document("_id", requestId).append("expiresAt", Date.from(expiresAt)), COLLECTION);
        } catch (DuplicateKeyException e) {
            throw new SsoException("SAML_REQUEST_EXISTS");
        }
    }

    @Override
    public boolean consume(String requestId) {
        if (requestId == null) {
            return false;
        }
        Document removed = template.findAndRemove(new Query(Criteria.where("_id").is(requestId)), Document.class, COLLECTION);
        return removed != null && removed.getDate("expiresAt").toInstant().isAfter(clock.instant());
    }
}
