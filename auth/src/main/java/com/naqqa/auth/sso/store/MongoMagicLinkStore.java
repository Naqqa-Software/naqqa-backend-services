package com.naqqa.auth.sso.store;

import com.naqqa.sso.otp.MagicLinkRecord;
import com.naqqa.sso.otp.MagicLinkStore;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.util.Optional;

public class MongoMagicLinkStore implements MagicLinkStore {

    static final String COLLECTION = "sso_magic_links";

    private final MongoTemplate template;
    private final Clock clock;

    public MongoMagicLinkStore(MongoTemplate template, Clock clock) {
        this.template = template;
        this.clock = clock;
    }

    @Override
    public void save(MagicLinkRecord r) {
        Document doc = new Document("_id", r.tokenHash())
                .append("userId", r.userId())
                .append("redirect", r.redirect())
                .append("bindingHash", r.bindingHash())
                .append("expiresAt", MongoSupport.date(r.expiresAt()))
                .append("used", r.used());
        template.save(doc, COLLECTION);
    }

    @Override
    public Optional<MagicLinkRecord> find(String tokenHash) {
        Document d = template.findById(tokenHash, Document.class, COLLECTION);
        if (d == null) {
            return Optional.empty();
        }
        return Optional.of(new MagicLinkRecord(tokenHash, d.getString("userId"), d.getString("redirect"), d.getString("bindingHash"),
                MongoSupport.instant(d.getDate("expiresAt")), MongoSupport.flag(d, "used")));
    }

    public long deleteByUser(String userId) {
        return template.remove(new Query(Criteria.where("userId").is(userId)), COLLECTION).getDeletedCount();
    }

    @Override
    public boolean markUsed(String tokenHash) {
        Query query = new Query(Criteria.where("_id").is(tokenHash).and("used").is(false)
                .and("expiresAt").gt(MongoSupport.date(clock.instant())));
        Document before = template.findAndModify(query, new Update().set("used", true), FindAndModifyOptions.options().returnNew(false), Document.class, COLLECTION);
        return before != null;
    }
}
