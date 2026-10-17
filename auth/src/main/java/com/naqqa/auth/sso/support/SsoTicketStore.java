package com.naqqa.auth.sso.support;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class SsoTicketStore {

    public static final String COLLECTION = "sso_tickets";

    public record Ticket(String kind, String id, String userId, Map<String, String> data, Instant expiresAt) {
    }

    private final MongoTemplate template;
    private final Clock clock;

    public SsoTicketStore(MongoTemplate template, Clock clock) {
        this.template = template;
        this.clock = clock;
        template.indexOps(COLLECTION).ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ofMinutes(10)).named("ttl_expiresAt"));
        template.indexOps(COLLECTION).ensureIndex(new Index().on("kind", Sort.Direction.ASC).on("userId", Sort.Direction.ASC).named("kind_user"));
    }

    private static String key(String kind, String id) {
        return kind + "|" + id;
    }

    public void put(String kind, String id, String userId, Map<String, String> data, Duration ttl) {
        Document doc = new Document("_id", key(kind, id))
                .append("kind", kind)
                .append("userId", userId)
                .append("data", new Document(data == null ? Map.of() : data))
                .append("expiresAt", Date.from(clock.instant().plus(ttl)));
        template.save(doc, COLLECTION);
    }

    public boolean markUsed(String kind, String id, Instant expiresAt) {
        try {
            template.insert(new Document("_id", key(kind, id)).append("kind", kind).append("expiresAt", Date.from(expiresAt)), COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<Ticket> peek(String kind, String id) {
        Document d = template.findById(key(kind, id), Document.class, COLLECTION);
        return live(kind, id, d);
    }

    public Optional<Ticket> take(String kind, String id) {
        Document d = template.findAndRemove(new Query(Criteria.where("_id").is(key(kind, id))), Document.class, COLLECTION);
        return live(kind, id, d);
    }

    public void remove(String kind, String id) {
        template.remove(new Query(Criteria.where("_id").is(key(kind, id))), COLLECTION);
    }

    public void removeForUser(String kind, String userId) {
        template.remove(new Query(Criteria.where("kind").is(kind).and("userId").is(userId)), COLLECTION);
    }

    public void removeForUserExcept(String kind, String userId, String keepId) {
        template.remove(new Query(Criteria.where("kind").is(kind).and("userId").is(userId).and("_id").ne(key(kind, keepId == null ? "" : keepId))),
                COLLECTION);
    }

    public long removeAllForUser(String userId) {
        return template.remove(new Query(Criteria.where("userId").is(userId)), COLLECTION).getDeletedCount();
    }

    private Optional<Ticket> live(String kind, String id, Document d) {
        if (d == null) {
            return Optional.empty();
        }
        Instant expires = d.getDate("expiresAt").toInstant();
        if (!clock.instant().isBefore(expires)) {
            return Optional.empty();
        }
        Map<String, String> data = new LinkedHashMap<>();
        Document raw = d.get("data", Document.class);
        if (raw != null) {
            raw.forEach((k, v) -> data.put(k, v == null ? null : v.toString()));
        }
        return Optional.of(new Ticket(kind, id, d.getString("userId"), data, expires));
    }
}
