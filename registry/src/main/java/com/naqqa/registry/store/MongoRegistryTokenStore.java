package com.naqqa.registry.store;

import com.naqqa.registry.domain.RegistryToken;
import com.naqqa.registry.spi.RegistryTokenStore;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

public class MongoRegistryTokenStore implements RegistryTokenStore {

    static final String ID = "_id";
    static final String CUSTOMER_ID = "customerId";
    static final String CREATED_AT = "createdAt";
    static final String LAST_USED_AT = "lastUsedAt";

    private final MongoOperations mongo;
    private final String tokens;

    public MongoRegistryTokenStore(MongoOperations mongo, String collectionPrefix) {
        this.mongo = mongo;
        this.tokens = (collectionPrefix == null ? "" : collectionPrefix) + "tokens";
    }

    public String collection() {
        return tokens;
    }

    @SuppressWarnings("deprecation")
    public void ensureIndexes() {
        mongo.indexOps(tokens).ensureIndex(new Index().on(CUSTOMER_ID, Sort.Direction.ASC).on(CREATED_AT, Sort.Direction.DESC));
    }

    @Override
    public void save(RegistryToken token) {
        mongo.save(toDocument(token), tokens);
    }

    @Override
    public Optional<RegistryToken> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongo.findById(id, Document.class, tokens)).map(MongoRegistryTokenStore::fromDocument);
    }

    @Override
    public List<RegistryToken> list(String customerId) {
        Query query = customerId == null ? new Query() : new Query(Criteria.where(CUSTOMER_ID).is(customerId));
        query.with(Sort.by(Sort.Direction.DESC, CREATED_AT).and(Sort.by(Sort.Direction.ASC, ID)));
        List<RegistryToken> result = new ArrayList<>();
        for (Document document : mongo.find(query, Document.class, tokens)) {
            result.add(fromDocument(document));
        }
        return result;
    }

    @Override
    public void touch(String id, Instant at) {
        mongo.updateFirst(new Query(Criteria.where(ID).is(id)), new Update().set(LAST_USED_AT, Date.from(at)), tokens);
    }

    static Document toDocument(RegistryToken token) {
        return new Document(ID, token.id())
                .append("hash", token.hash())
                .append(CUSTOMER_ID, token.customerId())
                .append("customerName", token.customerName())
                .append("label", token.label())
                .append("packageSets", token.packageSets())
                .append("packages", token.packages())
                .append(CREATED_AT, date(token.createdAt()))
                .append("createdBy", token.createdBy())
                .append("expiresAt", date(token.expiresAt()))
                .append("revokedAt", date(token.revokedAt()))
                .append("revokedBy", token.revokedBy())
                .append("revokeReason", token.revokeReason())
                .append(LAST_USED_AT, date(token.lastUsedAt()));
    }

    static RegistryToken fromDocument(Document d) {
        return new RegistryToken(d.getString(ID), d.getString("hash"), d.getString(CUSTOMER_ID), d.getString("customerName"),
                d.getString("label"), d.getList("packageSets", String.class), d.getList("packages", String.class),
                instant(d.getDate(CREATED_AT)), d.getString("createdBy"), instant(d.getDate("expiresAt")),
                instant(d.getDate("revokedAt")), d.getString("revokedBy"), d.getString("revokeReason"),
                instant(d.getDate(LAST_USED_AT)));
    }

    private static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
