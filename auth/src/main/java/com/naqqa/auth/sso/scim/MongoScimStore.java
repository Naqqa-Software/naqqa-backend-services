package com.naqqa.auth.sso.scim;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.UpdateResult;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.scim.ScimResource;
import com.naqqa.sso.scim.ScimResourceStore;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public abstract class MongoScimStore implements ScimResourceStore {

    protected final MongoTemplate template;
    protected final String collection;
    private final String uniqueAttribute;
    private final String uniqueField;

    protected MongoScimStore(MongoTemplate template, String collection, String uniqueAttribute, String uniqueField) {
        this.template = template;
        this.collection = collection;
        this.uniqueAttribute = uniqueAttribute;
        this.uniqueField = uniqueField;
        template.indexOps(collection).ensureIndex(new Index().on("tenantId", Sort.Direction.ASC).on(uniqueField, Sort.Direction.ASC)
                .unique().partial(PartialIndexFilter.of(Criteria.where(uniqueField).exists(true))).named("tenant_" + uniqueField));
        template.indexOps(collection).ensureIndex(new Index().on("tenantId", Sort.Direction.ASC).on("created", Sort.Direction.ASC)
                .on("rid", Sort.Direction.ASC).named("tenant_created"));
        template.indexOps(collection).ensureIndex(new Index().on("tenantId", Sort.Direction.ASC).on("rid", Sort.Direction.ASC)
                .named("tenant_rid"));
    }

    public String collection() {
        return collection;
    }

    private MongoCollection<Document> raw() {
        return template.getCollection(collection);
    }

    @Override
    public Optional<ScimResource> find(String tenantId, String id) {
        if (tenantId == null || id == null) {
            return Optional.empty();
        }
        Document d = raw().find(Filters.eq("_id", ScimDocuments.key(tenantId, id))).first();
        return Optional.ofNullable(d).map(ScimDocuments::fromDocument);
    }

    @Override
    public List<ScimResource> list(String tenantId) {
        throw new SsoException("scim.too_many");
    }

    @Override
    public ScimResource insert(ScimResource resource) {
        try {
            raw().insertOne(ScimDocuments.toDocument(resource, uniqueAttribute, uniqueField));
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                throw new SsoException("scim.uniqueness");
            }
            throw e;
        }
        return resource;
    }

    @Override
    public ScimResource update(ScimResource next, long expectedVersion) {
        if (next.version() != expectedVersion + 1) {
            throw new SsoException("scim.version_conflict");
        }
        UpdateResult result;
        try {
            result = raw().replaceOne(Filters.and(Filters.eq("_id", ScimDocuments.key(next.tenantId(), next.id())),
                    Filters.eq("version", expectedVersion)), ScimDocuments.toDocument(next, uniqueAttribute, uniqueField));
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                throw new SsoException("scim.uniqueness");
            }
            throw e;
        }
        if (result.getMatchedCount() == 0) {
            if (find(next.tenantId(), next.id()).isEmpty()) {
                throw new SsoException("scim.not_found");
            }
            throw new SsoException("scim.version_conflict");
        }
        return next;
    }

    @Override
    public boolean delete(String tenantId, String id) {
        return raw().deleteOne(Filters.eq("_id", ScimDocuments.key(tenantId, id))).getDeletedCount() == 1;
    }

    protected Optional<ScimResource> byUnique(String tenantId, String value) {
        if (tenantId == null || value == null) {
            return Optional.empty();
        }
        Document d = raw().find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq(uniqueField, value.toLowerCase(Locale.ROOT)))).first();
        return Optional.ofNullable(d).map(ScimDocuments::fromDocument);
    }

    static Criteria tenant(String tenantId, Criteria filter) {
        Criteria t = Criteria.where("tenantId").is(tenantId);
        return filter == null ? t : new Criteria().andOperator(t, filter);
    }

    public long count(String tenantId, Criteria filter) {
        return template.count(new Query(tenant(tenantId, filter)), collection);
    }

    public List<ScimResource> query(String tenantId, Criteria filter, Sort sort, long skip, int limit) {
        Query query = new Query(tenant(tenantId, filter)).with(sort).skip(skip).limit(limit);
        List<ScimResource> out = new ArrayList<>();
        for (Document d : template.find(query, Document.class, collection)) {
            out.add(ScimDocuments.fromDocument(d));
        }
        return out;
    }

    public List<ScimResource> byIds(String tenantId, Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return query(tenantId, Criteria.where("rid").in(ids), Sort.by("created", "rid"), 0, ids.size());
    }

    public List<ScimResource> withMember(String tenantId, Collection<String> userIds, int limit) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return query(tenantId, Criteria.where("attributes.members.value").in(userIds), Sort.by("created", "rid"), 0, limit);
    }
}
