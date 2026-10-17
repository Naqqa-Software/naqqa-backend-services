package com.naqqa.auth.sso.admin;

import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.store.MongoSupport;
import com.naqqa.sso.billing.PlanQuotas;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class SsoTenantStore {

    public static final String COLLECTION = "sso_tenants";
    public static final String ACTIVE = "active";
    public static final String SUSPENDED = "suspended";
    public static final int MAX_TENANTS = 1000;

    public record Tenant(String id, String name, String plan, String status, List<String> admins, Instant createdAt, Instant updatedAt) {

        public Tenant {
            admins = List.copyOf(admins == null ? List.of() : admins);
        }

        public boolean suspended() {
            return SUSPENDED.equals(status);
        }

        public PlanQuotas quotas() {
            PlanQuotas q = SsoTenantStore.plan(plan);
            return q == null ? PlanQuotas.free() : q;
        }
    }

    private final MongoTemplate template;
    private final Clock clock;
    private final String defaultPlan;

    public SsoTenantStore(MongoTemplate template, Clock clock) {
        this(template, clock, "ENTERPRISE");
    }

    public SsoTenantStore(MongoTemplate template, Clock clock, String defaultPlan) {
        this.template = template;
        this.clock = clock;
        this.defaultPlan = defaultPlan;
        ensureDefault();
    }

    public static PlanQuotas plan(String name) {
        if (name == null) {
            return null;
        }
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "FREE" -> PlanQuotas.free();
            case "PRO" -> PlanQuotas.pro();
            case "ENTERPRISE" -> PlanQuotas.enterprise();
            default -> null;
        };
    }

    public void ensureDefault() {
        if (template.findById(SsoSessionManager.TENANT, Document.class, COLLECTION) != null) {
            return;
        }
        Instant now = clock.instant();
        try {
            insert(new Tenant(SsoSessionManager.TENANT, "Default", defaultPlan, ACTIVE, List.of(), now, now));
        } catch (DuplicateKeyException ignored) {
        }
    }

    public Optional<Tenant> find(String id) {
        if (id == null || id.isEmpty() || id.length() > 64) {
            return Optional.empty();
        }
        return Optional.ofNullable(template.findById(id, Document.class, COLLECTION)).map(SsoTenantStore::fromDocument);
    }

    public List<Tenant> list() {
        List<Tenant> out = new ArrayList<>();
        Query q = new Query().with(Sort.by("_id")).limit(MAX_TENANTS);
        for (Document d : template.find(q, Document.class, COLLECTION)) {
            out.add(fromDocument(d));
        }
        return out;
    }

    public void insert(Tenant t) {
        template.insert(toDocument(t), COLLECTION);
    }

    public void save(Tenant t) {
        template.save(toDocument(t), COLLECTION);
    }

    public long removeAdmin(String userId) {
        return template.updateMulti(new Query(Criteria.where("admins").is(userId)), new Update().pull("admins", userId), COLLECTION)
                .getModifiedCount();
    }

    static Document toDocument(Tenant t) {
        return new Document("_id", t.id())
                .append("name", t.name())
                .append("plan", t.plan())
                .append("status", t.status())
                .append("admins", new ArrayList<>(t.admins()))
                .append("createdAt", MongoSupport.date(t.createdAt()))
                .append("updatedAt", MongoSupport.date(t.updatedAt()));
    }

    static Tenant fromDocument(Document d) {
        return new Tenant(d.getString("_id"), d.getString("name"), d.getString("plan"), d.getString("status"),
                d.getList("admins", String.class, List.of()), MongoSupport.instant(d.getDate("createdAt")),
                MongoSupport.instant(d.getDate("updatedAt")));
    }
}
