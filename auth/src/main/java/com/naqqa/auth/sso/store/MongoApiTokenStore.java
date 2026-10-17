package com.naqqa.auth.sso.store;

import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenRecord;
import com.naqqa.sso.tokens.ApiTokenStore;
import com.naqqa.sso.tokens.Cidr;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class MongoApiTokenStore implements ApiTokenStore {

    static final String COLLECTION = "sso_api_tokens";
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    public record Extras(String clientId, Instant lastUsedAt) {
    }

    private final MongoTemplate template;

    public MongoApiTokenStore(MongoTemplate template) {
        this.template = template;
    }

    private static ApiTokenRecord fromDocument(Document d) {
        List<Cidr> allowlist = new ArrayList<>();
        for (Document c : d.getList("allowlist", Document.class, List.of())) {
            allowlist.add(new Cidr(MongoSupport.bytes(c.get("network")), MongoSupport.integer(c, "prefix")));
        }
        return new ApiTokenRecord(d.getString("_id"), d.getString("tenantId"), ApiTokenKind.valueOf(d.getString("kind")),
                ApiTokenEnv.valueOf(d.getString("env")), d.getString("name"), new HashSet<>(d.getList("scopes", String.class, List.of())),
                allowlist, d.getString("hash"), d.getString("previousHash"), MongoSupport.instant(d.getDate("previousValidUntil")),
                MongoSupport.instant(d.getDate("createdAt")), MongoSupport.instant(d.getDate("rotatedAt")),
                MongoSupport.instant(d.getDate("revokedAt")), MongoSupport.integer(d, "failedAttempts"),
                MongoSupport.instant(d.getDate("lockedUntil")));
    }

    @Override
    public Optional<ApiTokenRecord> findById(String id) {
        return Optional.ofNullable(template.findById(id, Document.class, COLLECTION)).map(MongoApiTokenStore::fromDocument);
    }

    @Override
    public List<ApiTokenRecord> findByTenant(String tenantId) {
        List<ApiTokenRecord> out = new ArrayList<>();
        for (Document d : template.find(new Query(Criteria.where("tenantId").is(tenantId)), Document.class, COLLECTION)) {
            out.add(fromDocument(d));
        }
        return out;
    }

    @Override
    public void save(ApiTokenRecord r) {
        List<Document> allowlist = cidrs(r.allowlist());
        Update update = new Update()
                .set("tenantId", r.tenantId())
                .set("kind", r.kind().name())
                .set("env", r.env().name())
                .set("name", r.name())
                .set("scopes", new ArrayList<>(r.scopes()))
                .set("allowlist", allowlist)
                .set("hash", r.hash())
                .set("previousHash", r.previousHash())
                .set("previousValidUntil", MongoSupport.date(r.previousValidUntil()))
                .set("createdAt", MongoSupport.date(r.createdAt()))
                .set("rotatedAt", MongoSupport.date(r.rotatedAt()))
                .set("failedAttempts", r.failedAttempts())
                .set("lockedUntil", MongoSupport.date(r.lockedUntil()));
        if (r.revokedAt() != null) {
            update.set("revokedAt", MongoSupport.date(r.revokedAt()));
        }
        template.upsert(new Query(Criteria.where("_id").is(r.id())), update, COLLECTION);
    }

    @Override
    public int recordFailure(String id) {
        Document d = template.findAndModify(new Query(Criteria.where("_id").is(id)), new Update().inc("failedAttempts", 1),
                FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        return d == null ? 0 : MongoSupport.integer(d, "failedAttempts");
    }

    @Override
    public void resetFailures(String id) {
        template.updateFirst(new Query(Criteria.where("_id").is(id)), new Update().set("failedAttempts", 0).set("lockedUntil", null), COLLECTION);
    }

    @Override
    public boolean revoke(String id, Instant at) {
        Query live = new Query(Criteria.where("_id").is(id).and("revokedAt").is(null));
        return template.updateFirst(live, new Update().set("revokedAt", MongoSupport.date(at)), COLLECTION).getModifiedCount() > 0;
    }

    @Override
    public boolean rotate(String id, String expectedHash, String newHash, String previousHash, Instant previousValidUntil, Instant rotatedAt) {
        Query current = new Query(Criteria.where("_id").is(id).and("hash").is(expectedHash).and("revokedAt").is(null));
        Update update = new Update().set("hash", newHash).set("previousHash", previousHash)
                .set("previousValidUntil", MongoSupport.date(previousValidUntil)).set("rotatedAt", MongoSupport.date(rotatedAt));
        return template.updateFirst(current, update, COLLECTION).getModifiedCount() > 0;
    }

    @Override
    public boolean updateScopes(String id, Set<String> scopes) {
        return template.updateFirst(new Query(Criteria.where("_id").is(id)), new Update().set("scopes", new ArrayList<>(scopes)), COLLECTION)
                .getMatchedCount() > 0;
    }

    @Override
    public boolean updateAllowlist(String id, List<Cidr> allowlist) {
        return template.updateFirst(new Query(Criteria.where("_id").is(id)), new Update().set("allowlist", cidrs(allowlist)), COLLECTION)
                .getMatchedCount() > 0;
    }

    private static List<Document> cidrs(List<Cidr> allowlist) {
        List<Document> out = new ArrayList<>();
        for (Cidr c : allowlist) {
            out.add(new Document("network", c.network()).append("prefix", c.prefixLength()));
        }
        return out;
    }

    public void bindClient(String id, String clientId) {
        template.updateFirst(new Query(Criteria.where("_id").is(id)), new Update().set("clientId", clientId), COLLECTION);
    }

    public void touch(String id, Instant now) {
        Criteria stale = new Criteria().orOperator(Criteria.where("lastUsedAt").exists(false),
                Criteria.where("lastUsedAt").lt(MongoSupport.date(now.minus(TOUCH_INTERVAL))));
        template.updateFirst(new Query(new Criteria().andOperator(Criteria.where("_id").is(id), stale)),
                new Update().set("lastUsedAt", MongoSupport.date(now)), COLLECTION);
    }

    public Extras extras(String id) {
        Document d = template.findById(id, Document.class, COLLECTION);
        return d == null ? new Extras(null, null) : new Extras(d.getString("clientId"), MongoSupport.instant(d.getDate("lastUsedAt")));
    }

    public Map<String, Extras> extrasByTenant(String tenantId) {
        Map<String, Extras> out = new LinkedHashMap<>();
        Query q = new Query(Criteria.where("tenantId").is(tenantId));
        q.fields().include("clientId").include("lastUsedAt");
        for (Document d : template.find(q, Document.class, COLLECTION)) {
            out.put(d.getString("_id"), new Extras(d.getString("clientId"), MongoSupport.instant(d.getDate("lastUsedAt"))));
        }
        return out;
    }

    public List<String> idsByClient(String clientId) {
        List<String> out = new ArrayList<>();
        Query q = new Query(Criteria.where("clientId").is(clientId));
        q.fields().include("_id");
        for (Document d : template.find(q, Document.class, COLLECTION)) {
            out.add(d.getString("_id"));
        }
        return out;
    }

    @Override
    public boolean delete(String id) {
        return template.remove(new Query(Criteria.where("_id").is(id)), COLLECTION).getDeletedCount() > 0;
    }
}
