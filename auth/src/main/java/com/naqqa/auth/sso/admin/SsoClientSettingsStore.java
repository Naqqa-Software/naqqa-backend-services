package com.naqqa.auth.sso.admin;

import com.naqqa.auth.sso.store.MongoSupport;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SsoClientSettingsStore {

    public static final String COLLECTION = "sso_client_settings";
    public static final List<String> METHODS = List.of("password", "magicLink", "emailOtp", "smsOtp", "totp", "recovery", "passkey",
            "biometric", "securityKey", "push", "saml", "samlSp");
    static final Duration CACHE_TTL = Duration.ofSeconds(5);
    static final int CACHE_MAX = 10_000;

    public record Settings(String clientId, String tenantId, Boolean enabled, Map<String, Boolean> methods, List<String> socialProviders,
                           List<String> allowedOrigins, String brandingName, String logoUrl, Instant updatedAt) {

        public Settings {
            Map<String, Boolean> m = new LinkedHashMap<>();
            if (methods != null) {
                methods.forEach((k, v) -> {
                    if (METHODS.contains(k) && v != null) {
                        m.put(k, v);
                    }
                });
            }
            methods = Collections.unmodifiableMap(m);
            socialProviders = socialProviders == null ? null : List.copyOf(socialProviders);
            allowedOrigins = List.copyOf(allowedOrigins == null ? List.of() : allowedOrigins);
        }

        public Boolean method(String name) {
            return methods.get(name);
        }
    }

    private record Cached(Optional<Settings> value, long expiresAt) {
    }

    private final MongoTemplate template;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile Cached origins;

    public SsoClientSettingsStore(MongoTemplate template) {
        this.template = template;
        template.indexOps(COLLECTION).ensureIndex(new Index().on("tenantId", Sort.Direction.ASC).named("tenantId"));
    }

    public Optional<Settings> find(String clientId) {
        if (clientId == null) {
            return Optional.empty();
        }
        long now = System.nanoTime();
        Cached c = cache.get(clientId);
        if (c != null && now - c.expiresAt() < 0) {
            return c.value();
        }
        Document d = template.findById(clientId, Document.class, COLLECTION);
        Optional<Settings> value = Optional.ofNullable(d).map(SsoClientSettingsStore::fromDocument);
        if (cache.size() >= CACHE_MAX) {
            cache.clear();
        }
        cache.put(clientId, new Cached(value, now + CACHE_TTL.toNanos()));
        return value;
    }

    public List<Settings> listByTenant(String tenantId) {
        List<Settings> out = new ArrayList<>();
        for (Document d : template.find(new Query(Criteria.where("tenantId").is(tenantId)), Document.class, COLLECTION)) {
            out.add(fromDocument(d));
        }
        return out;
    }

    public Set<String> allOrigins() {
        long now = System.nanoTime();
        Cached c = origins;
        Set<String> out = new LinkedHashSet<>();
        if (c != null && now - c.expiresAt() < 0) {
            c.value().ifPresent(s -> out.addAll(s.allowedOrigins()));
            return out;
        }
        Query q = new Query(Criteria.where("allowedOrigins.0").exists(true));
        q.fields().include("allowedOrigins");
        for (Document d : template.find(q, Document.class, COLLECTION)) {
            out.addAll(d.getList("allowedOrigins", String.class, List.of()));
        }
        origins = new Cached(Optional.of(new Settings("*", "*", null, null, null, new ArrayList<>(out), null, null, null)),
                now + CACHE_TTL.toNanos());
        return out;
    }

    public void save(Settings s) {
        template.save(toDocument(s), COLLECTION);
        invalidate(s.clientId());
    }

    public void delete(String clientId) {
        template.remove(new Query(Criteria.where("_id").is(clientId)), COLLECTION);
        invalidate(clientId);
    }

    public void invalidate(String clientId) {
        cache.remove(clientId);
        origins = null;
    }

    static Document toDocument(Settings s) {
        return new Document("_id", s.clientId())
                .append("tenantId", s.tenantId())
                .append("enabled", s.enabled())
                .append("methods", new Document(new LinkedHashMap<>(s.methods())))
                .append("socialProviders", s.socialProviders() == null ? null : new ArrayList<>(s.socialProviders()))
                .append("allowedOrigins", new ArrayList<>(s.allowedOrigins()))
                .append("brandingName", s.brandingName())
                .append("logoUrl", s.logoUrl())
                .append("updatedAt", MongoSupport.date(s.updatedAt()));
    }

    static Settings fromDocument(Document d) {
        Map<String, Boolean> methods = new LinkedHashMap<>();
        Document m = d.get("methods", Document.class);
        if (m != null) {
            for (String k : METHODS) {
                Object v = m.get(k);
                if (v instanceof Boolean b) {
                    methods.put(k, b);
                }
            }
        }
        Object enabled = d.get("enabled");
        return new Settings(d.getString("_id"), d.getString("tenantId"), enabled instanceof Boolean b ? b : null, methods,
                d.get("socialProviders") == null ? null : d.getList("socialProviders", String.class),
                d.getList("allowedOrigins", String.class, List.of()), d.getString("brandingName"), d.getString("logoUrl"),
                MongoSupport.instant(d.getDate("updatedAt")));
    }
}
