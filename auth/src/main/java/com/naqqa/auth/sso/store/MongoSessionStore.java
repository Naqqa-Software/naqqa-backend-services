package com.naqqa.auth.sso.store;

import com.naqqa.sso.session.Session;
import com.naqqa.sso.session.SessionStore;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

public class MongoSessionStore implements SessionStore {

    public static final String COLLECTION = "sso_sessions";
    public static final Duration GRACE = Duration.ofHours(1);

    private final MongoTemplate template;
    private final Duration retention;

    public MongoSessionStore(MongoTemplate template) {
        this(template, Duration.ofDays(30));
    }

    public MongoSessionStore(MongoTemplate template, Duration absoluteTimeout) {
        this.template = template;
        this.retention = absoluteTimeout.plus(GRACE);
    }

    private static Session fromDocument(Document d) {
        return new Session(d.getString("_id"), d.getString("userId"), d.getString("tenantId"),
                new HashSet<>(d.getList("clientIds", String.class, List.of())),
                MongoSupport.instant(d.getDate("created")), MongoSupport.instant(d.getDate("lastSeen")),
                d.getString("deviceId"), d.getString("ip"), d.getString("userAgentHash"),
                new HashSet<>(d.getList("amr", String.class, List.of())), d.getString("acr"),
                MongoSupport.instant(d.getDate("authTime")));
    }

    private Document toDocument(Session s) {
        return new Document("_id", s.idHash())
                .append("userId", s.userId())
                .append("tenantId", s.tenantId())
                .append("clientIds", new ArrayList<>(s.clientIds()))
                .append("created", MongoSupport.date(s.created()))
                .append("lastSeen", MongoSupport.date(s.lastSeen()))
                .append("deviceId", s.deviceId())
                .append("ip", s.ip())
                .append("userAgentHash", s.userAgentHash())
                .append("amr", new ArrayList<>(s.amr()))
                .append("acr", s.acr())
                .append("authTime", MongoSupport.date(s.authTime()))
                .append("purgeAt", MongoSupport.date(s.created().plus(retention)));
    }

    @Override
    public void save(Session s) {
        template.save(toDocument(s), COLLECTION);
    }

    @Override
    public boolean replace(Session s) {
        return template.getCollection(COLLECTION).replaceOne(new Document("_id", s.idHash()), toDocument(s)).getMatchedCount() == 1;
    }

    @Override
    public Optional<Session> find(String idHash) {
        return Optional.ofNullable(template.findById(idHash, Document.class, COLLECTION)).map(MongoSessionStore::fromDocument);
    }

    @Override
    public void delete(String idHash) {
        template.remove(new Query(Criteria.where("_id").is(idHash)), COLLECTION);
    }

    @Override
    public List<Session> listByUser(String userId) {
        List<Session> out = new ArrayList<>();
        for (Document d : template.find(new Query(Criteria.where("userId").is(userId)), Document.class, COLLECTION)) {
            out.add(fromDocument(d));
        }
        return out;
    }

    @Override
    public int deleteByUser(String userId) {
        return (int) template.remove(new Query(Criteria.where("userId").is(userId)), COLLECTION).getDeletedCount();
    }
}
