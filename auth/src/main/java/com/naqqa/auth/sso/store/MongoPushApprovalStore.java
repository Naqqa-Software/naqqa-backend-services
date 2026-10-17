package com.naqqa.auth.sso.store;

import com.naqqa.sso.otp.PushApproval;
import com.naqqa.sso.otp.PushApprovalRecord;
import com.naqqa.sso.otp.PushApprovalStore;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

public class MongoPushApprovalStore implements PushApprovalStore {

    static final String COLLECTION = "sso_push_approvals";
    private static final Duration RETENTION = Duration.ofHours(1);

    private final MongoTemplate template;

    public MongoPushApprovalStore(MongoTemplate template) {
        this.template = template;
    }

    @Override
    public void create(PushApprovalRecord r) {
        Document d = new Document("_id", r.id())
                .append("userId", r.userId())
                .append("number", r.number())
                .append("expiresAt", MongoSupport.date(r.expiresAt()))
                .append("purgeAt", MongoSupport.date(r.expiresAt().plus(RETENTION)))
                .append("status", r.status().name())
                .append("mismatches", r.mismatches());
        template.insert(d, COLLECTION);
    }

    public long deleteByUser(String userId) {
        return template.remove(new Query(Criteria.where("userId").is(userId)), COLLECTION).getDeletedCount();
    }

    @Override
    public Optional<PushApprovalRecord> find(String id) {
        Document d = template.findById(id, Document.class, COLLECTION);
        if (d == null) {
            return Optional.empty();
        }
        return Optional.of(new PushApprovalRecord(id, d.getString("userId"), d.getString("number"),
                MongoSupport.instant(d.getDate("expiresAt")), PushApproval.Status.valueOf(d.getString("status")),
                MongoSupport.integer(d, "mismatches")));
    }

    @Override
    public boolean transition(String id, PushApproval.Status from, PushApproval.Status to) {
        Query query = new Query(Criteria.where("_id").is(id).and("status").is(from.name()));
        Document updated = template.findAndModify(query, new Update().set("status", to.name()),
                FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        return updated != null;
    }

    @Override
    public OptionalInt addMismatch(String id) {
        Query query = new Query(Criteria.where("_id").is(id).and("status").is(PushApproval.Status.PENDING.name()));
        Document updated = template.findAndModify(query, new Update().inc("mismatches", 1),
                FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        return updated == null ? OptionalInt.empty() : OptionalInt.of(MongoSupport.integer(updated, "mismatches"));
    }
}
