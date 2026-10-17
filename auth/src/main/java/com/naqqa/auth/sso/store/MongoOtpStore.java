package com.naqqa.auth.sso.store;

import com.naqqa.sso.otp.OtpRecord;
import com.naqqa.sso.otp.OtpStore;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public class MongoOtpStore implements OtpStore {

    static final String COLLECTION = "sso_otp";
    static final String SENDS = "sso_otp_sends";
    private static final Duration SEND_RETENTION = Duration.ofDays(2);

    private final MongoTemplate template;

    public MongoOtpStore(MongoTemplate template) {
        this.template = template;
    }

    @Override
    public void save(OtpRecord r) {
        Document doc = new Document("_id", r.id())
                .append("destinationKey", r.destinationKey())
                .append("purpose", r.purpose())
                .append("codeMac", r.codeMac())
                .append("createdAt", MongoSupport.date(r.createdAt()))
                .append("expiresAt", MongoSupport.date(r.expiresAt()))
                .append("attempts", r.attempts())
                .append("consumed", r.consumed());
        Query live = new Query(Criteria.where("_id").is(r.id()).and("consumed").is(false));
        Update update = new Update().max("attempts", r.attempts()).set("consumed", r.consumed());
        if (template.updateFirst(live, update, COLLECTION).getMatchedCount() > 0) {
            return;
        }
        try {
            template.insert(doc, COLLECTION);
        } catch (DuplicateKeyException e) {
            throw new OptimisticLockingFailureException("otp challenge already consumed");
        }
    }

    @Override
    public Optional<OtpRecord> find(String id) {
        Document d = template.findById(id, Document.class, COLLECTION);
        if (d == null) {
            return Optional.empty();
        }
        return Optional.of(toRecord(id, d));
    }

    private static OtpRecord toRecord(String id, Document d) {
        return new OtpRecord(id, d.getString("destinationKey"), d.getString("purpose"), d.getString("codeMac"),
                MongoSupport.instant(d.getDate("createdAt")), MongoSupport.instant(d.getDate("expiresAt")),
                MongoSupport.integer(d, "attempts"), MongoSupport.flag(d, "consumed"));
    }

    @Override
    public Optional<OtpRecord> recordAttempt(String id, int maxAttempts) {
        Query live = new Query(Criteria.where("_id").is(id).and("consumed").is(false).and("attempts").lt(maxAttempts));
        Document d = template.findAndModify(live, new Update().inc("attempts", 1), FindAndModifyOptions.options().returnNew(true),
                Document.class, COLLECTION);
        if (d == null) {
            return Optional.empty();
        }
        return Optional.of(toRecord(id, d));
    }

    @Override
    public boolean consume(String id) {
        Query live = new Query(Criteria.where("_id").is(id).and("consumed").is(false));
        return template.updateFirst(live, new Update().set("consumed", true), COLLECTION).getModifiedCount() > 0;
    }

    @Override
    public void recordSend(String destinationKey, Instant at) {
        Document doc = new Document("destinationKey", destinationKey)
                .append("at", MongoSupport.date(at))
                .append("expiresAt", MongoSupport.date(at.plus(SEND_RETENTION)));
        template.insert(doc, SENDS);
    }

    @Override
    public Optional<Instant> lastSend(String destinationKey) {
        Query query = new Query(Criteria.where("destinationKey").is(destinationKey)).with(Sort.by(Sort.Direction.DESC, "at")).limit(1);
        Document d = template.findOne(query, Document.class, SENDS);
        return Optional.ofNullable(d).map(x -> MongoSupport.instant(x.getDate("at")));
    }

    @Override
    public int sendsSince(String destinationKey, Instant since) {
        Query query = new Query(Criteria.where("destinationKey").is(destinationKey).and("at").gte(MongoSupport.date(since)));
        return (int) template.count(query, SENDS);
    }

    public long deleteByPurposes(Collection<String> purposes) {
        return template.remove(new Query(Criteria.where("purpose").in(purposes)), COLLECTION).getDeletedCount();
    }

    @Override
    public void invalidateDestination(String destinationKey) {
        template.updateMulti(new Query(Criteria.where("destinationKey").is(destinationKey)), new Update().set("consumed", true), COLLECTION);
    }
}
