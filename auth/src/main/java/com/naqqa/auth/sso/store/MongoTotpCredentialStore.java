package com.naqqa.auth.sso.store;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.mfa.OtpAlgorithm;
import com.naqqa.sso.mfa.TotpConfig;
import com.naqqa.sso.mfa.TotpCredential;
import com.naqqa.sso.mfa.TotpCredentialStore;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.Optional;

public class MongoTotpCredentialStore implements TotpCredentialStore {

    static final String COLLECTION = "sso_totp_credentials";

    private final MongoTemplate template;

    public MongoTotpCredentialStore(MongoTemplate template) {
        this.template = template;
    }

    @Override
    public Optional<TotpCredential> find(String userId) {
        Document d = template.findById(userId, Document.class, COLLECTION);
        if (d == null) {
            return Optional.empty();
        }
        TotpConfig config = new TotpConfig(OtpAlgorithm.valueOf(d.getString("algorithm")), MongoSupport.integer(d, "digits"),
                MongoSupport.integer(d, "stepSeconds"), MongoSupport.integer(d, "window"));
        return Optional.of(new TotpCredential(userId, MongoSupport.bytes(d.get("sealedSecret")), config, MongoSupport.flag(d, "confirmed"),
                MongoSupport.instant(d.getDate("createdAt")), MongoSupport.instant(d.getDate("confirmedAt"))));
    }

    @Override
    public void save(TotpCredential c) {
        Document doc = new Document("_id", c.userId())
                .append("sealedSecret", c.sealedSecret())
                .append("algorithm", c.config().algorithm().name())
                .append("digits", c.config().digits())
                .append("stepSeconds", c.config().stepSeconds())
                .append("window", c.config().window())
                .append("confirmed", c.confirmed())
                .append("createdAt", MongoSupport.date(c.createdAt()))
                .append("confirmedAt", MongoSupport.date(c.confirmedAt()));
        if (c.confirmed()) {
            template.save(doc, COLLECTION);
            return;
        }
        Query pending = new Query(Criteria.where("_id").is(c.userId()).and("confirmed").ne(true));
        Update update = new Update();
        doc.forEach((k, v) -> {
            if (!"_id".equals(k)) {
                update.set(k, v);
            }
        });
        try {
            template.upsert(pending, update, COLLECTION);
        } catch (DuplicateKeyException e) {
            throw new SsoException("totp_already_enrolled");
        }
    }

    @Override
    public boolean confirm(String userId, byte[] expectedSealedSecret, Instant confirmedAt) {
        Query pending = new Query(Criteria.where("_id").is(userId).and("confirmed").ne(true).and("sealedSecret").is(expectedSealedSecret));
        return template.updateFirst(pending, new Update().set("confirmed", true).set("confirmedAt", MongoSupport.date(confirmedAt)), COLLECTION)
                .getModifiedCount() == 1;
    }

    @Override
    public void delete(String userId) {
        template.remove(new Query(Criteria.where("_id").is(userId)), COLLECTION);
    }
}
