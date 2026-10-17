package com.naqqa.auth.sso.oidc;

import com.naqqa.sso.oidc.ConsentRecord;
import com.naqqa.sso.oidc.ConsentStore;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public class MongoConsentStore implements ConsentStore {

    public static final String COLLECTION = "sso_oidc_consents";

    private final MongoTemplate template;

    public MongoConsentStore(MongoTemplate template) {
        this.template = template;
        template.indexOps(COLLECTION).ensureIndex(new Index().on("tenantId", Sort.Direction.ASC).on("userId", Sort.Direction.ASC)
                .named("tenant_user"));
    }

    static String key(String tenantId, String userId, String clientId) {
        return tenantId + "|" + userId + "|" + clientId;
    }

    private static ConsentRecord record(Document d) {
        List<String> scopes = d.getList("scopes", String.class);
        return new ConsentRecord(d.getString("tenantId"), d.getString("userId"), d.getString("clientId"),
                new LinkedHashSet<>(scopes == null ? List.of() : scopes), d.getDate("grantedAt").toInstant());
    }

    @Override
    public Optional<ConsentRecord> find(String tenantId, String userId, String clientId) {
        Document d = template.findById(key(tenantId, userId, clientId), Document.class, COLLECTION);
        return d == null ? Optional.empty() : Optional.of(record(d));
    }

    @Override
    public void save(ConsentRecord r) {
        template.save(new Document("_id", key(r.tenantId(), r.userId(), r.clientId()))
                .append("tenantId", r.tenantId())
                .append("userId", r.userId())
                .append("clientId", r.clientId())
                .append("scopes", new ArrayList<>(new TreeSet<>(r.scopes())))
                .append("grantedAt", Date.from(r.grantedAt())), COLLECTION);
    }

    @Override
    public void grant(String tenantId, String userId, String clientId, Set<String> scopes, Instant at) {
        template.upsert(new Query(Criteria.where("_id").is(key(tenantId, userId, clientId))),
                new Update().setOnInsert("tenantId", tenantId).setOnInsert("userId", userId).setOnInsert("clientId", clientId)
                        .addToSet("scopes").each(new TreeSet<>(scopes).toArray()).set("grantedAt", Date.from(at)), COLLECTION);
    }

    @Override
    public boolean revoke(String tenantId, String userId, String clientId) {
        return template.remove(new Query(Criteria.where("_id").is(key(tenantId, userId, clientId))), COLLECTION).getDeletedCount() > 0;
    }

    @Override
    public List<ConsentRecord> listByUser(String tenantId, String userId) {
        Query q = new Query(Criteria.where("tenantId").is(tenantId).and("userId").is(userId)).with(Sort.by("clientId"));
        return template.find(q, Document.class, COLLECTION).stream().map(MongoConsentStore::record).toList();
    }

    public long deleteByUser(String userId) {
        return template.remove(new Query(Criteria.where("userId").is(userId)), COLLECTION).getDeletedCount();
    }
}
