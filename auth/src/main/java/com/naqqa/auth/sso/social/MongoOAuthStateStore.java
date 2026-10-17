package com.naqqa.auth.sso.social;

import com.naqqa.sso.oauth.StateRecord;
import com.naqqa.sso.oauth.StateStore;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.util.Date;
import java.util.Optional;

public class MongoOAuthStateStore implements StateStore {

    public static final String COLLECTION = "sso_oauth_states";

    private final MongoTemplate template;

    public MongoOAuthStateStore(MongoTemplate template) {
        this.template = template;
        template.indexOps(COLLECTION).ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO).named("ttl_expiresAt"));
    }

    @Override
    public void put(StateRecord r) {
        template.insert(new Document("_id", r.stateHash())
                .append("providerId", r.providerId())
                .append("nonce", r.nonce())
                .append("codeVerifier", r.codeVerifier())
                .append("bindingHash", r.bindingHash())
                .append("redirectAfter", r.redirectAfter())
                .append("expiresAt", Date.from(r.expiresAt())), COLLECTION);
    }

    @Override
    public Optional<StateRecord> consume(String stateHash) {
        Document d = template.findAndRemove(new Query(Criteria.where("_id").is(stateHash)), Document.class, COLLECTION);
        if (d == null) {
            return Optional.empty();
        }
        return Optional.of(new StateRecord(stateHash, d.getString("providerId"), d.getString("nonce"), d.getString("codeVerifier"),
                d.getString("bindingHash"), d.getString("redirectAfter"), d.getDate("expiresAt").toInstant()));
    }
}
