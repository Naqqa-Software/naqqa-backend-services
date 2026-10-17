package com.naqqa.auth.sso.store;

import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.webauthn.Ceremony;
import com.naqqa.sso.webauthn.ChallengeStore;
import com.naqqa.sso.webauthn.WebAuthnChallenge;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Clock;
import java.util.Optional;

public class MongoChallengeStore implements ChallengeStore {

    static final String COLLECTION = "sso_webauthn_challenges";

    private final MongoTemplate template;
    private final Clock clock;

    public MongoChallengeStore(MongoTemplate template, Clock clock) {
        this.template = template;
        this.clock = clock;
    }

    @Override
    public void save(WebAuthnChallenge c) {
        Document doc = new Document("_id", Bytes.b64url(c.value()))
                .append("ceremony", c.ceremony().name())
                .append("userHandle", c.userHandle())
                .append("userId", c.userId())
                .append("userName", c.userName())
                .append("userDisplayName", c.userDisplayName())
                .append("binding", c.binding())
                .append("expiresAt", MongoSupport.date(c.expiresAt()));
        template.save(doc, COLLECTION);
    }

    @Override
    public Optional<WebAuthnChallenge> consume(byte[] value) {
        if (value == null) {
            return Optional.empty();
        }
        Document d = template.findAndRemove(new Query(Criteria.where("_id").is(Bytes.b64url(value))), Document.class, COLLECTION);
        if (d == null || !clock.instant().isBefore(MongoSupport.instant(d.getDate("expiresAt")))) {
            return Optional.empty();
        }
        Object handle = d.get("userHandle");
        return Optional.of(new WebAuthnChallenge(value, Ceremony.valueOf(d.getString("ceremony")),
                handle == null ? null : MongoSupport.bytes(handle), d.getString("userId"), d.getString("userName"),
                d.getString("userDisplayName"), d.getString("binding"), MongoSupport.instant(d.getDate("expiresAt"))));
    }
}
