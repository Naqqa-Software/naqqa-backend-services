package com.naqqa.auth.sso.store;

import com.naqqa.sso.session.RefreshRecord;
import com.naqqa.sso.session.RefreshStore;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class MongoRefreshStore implements RefreshStore {

    static final String COLLECTION = "sso_refresh_tokens";
    static final String REVOKED = "sso_refresh_revoked_families";

    private final MongoTemplate template;
    private final Clock clock;
    private final Duration markerRetention;

    public MongoRefreshStore(MongoTemplate template, Clock clock, Duration markerRetention) {
        this.template = template;
        this.clock = clock;
        this.markerRetention = markerRetention;
    }

    @Override
    public void save(RefreshRecord r) {
        Document doc = new Document("_id", r.tokenHash())
                .append("familyId", r.familyId())
                .append("userId", r.userId())
                .append("clientId", r.clientId())
                .append("created", MongoSupport.date(r.created()))
                .append("familyExpiry", MongoSupport.date(r.familyExpiry()))
                .append("rotated", r.rotated());
        template.save(doc, COLLECTION);
    }

    @Override
    public Optional<RefreshRecord> find(String tokenHash) {
        Document d = template.findById(tokenHash, Document.class, COLLECTION);
        if (d == null) {
            return Optional.empty();
        }
        return Optional.of(new RefreshRecord(tokenHash, d.getString("familyId"), d.getString("userId"), d.getString("clientId"),
                MongoSupport.instant(d.getDate("created")), MongoSupport.instant(d.getDate("familyExpiry")), MongoSupport.flag(d, "rotated")));
    }

    @Override
    public boolean markRotated(String tokenHash) {
        Query query = new Query(Criteria.where("_id").is(tokenHash).and("rotated").is(false));
        Document before = template.findAndModify(query, new Update().set("rotated", true), FindAndModifyOptions.options().returnNew(false), Document.class, COLLECTION);
        return before != null;
    }

    private void mark(String familyId, Instant expiresAt) {
        template.upsert(new Query(Criteria.where("_id").is(familyId)),
                new Update().set("revokedAt", MongoSupport.date(clock.instant())).max("expiresAt", MongoSupport.date(expiresAt)), REVOKED);
    }

    @Override
    public void revokeFamily(String familyId) {
        Instant latest = clock.instant().plus(markerRetention);
        for (Document d : template.find(new Query(Criteria.where("familyId").is(familyId)), Document.class, COLLECTION)) {
            Instant expiry = MongoSupport.instant(d.getDate("familyExpiry"));
            if (expiry != null && expiry.isAfter(latest)) {
                latest = expiry;
            }
        }
        mark(familyId, latest);
    }

    @Override
    public boolean isFamilyRevoked(String familyId) {
        return template.exists(new Query(Criteria.where("_id").is(familyId)), REVOKED);
    }

    @Override
    public void revokeAllForUser(String userId) {
        Set<String> families = new HashSet<>(template.findDistinct(new Query(Criteria.where("userId").is(userId)), "familyId", COLLECTION, String.class));
        for (String family : families) {
            revokeFamily(family);
        }
    }
}
