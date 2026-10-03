package com.naqqa.analytics.reports;

import com.naqqa.analytics.model.SavedView;
import com.naqqa.analytics.web.AnalyticsException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SavedViewService {

    public static final int MAX_PER_USER = 100;

    private final MongoTemplate mongo;
    private final String collection;
    private final Clock clock;

    public SavedViewService(MongoTemplate mongo, String collection, Clock clock) {
        this.mongo = mongo;
        this.collection = collection;
        this.clock = clock;
    }

    public List<SavedView> list(String ownerId, String section) {
        Criteria c = Criteria.where("ownerId").is(ownerId);
        if (section != null && !section.isBlank()) {
            c = c.and("section").is(section);
        }
        return mongo.find(Query.query(c).with(Sort.by(Sort.Direction.DESC, "createdAt")), SavedView.class, collection);
    }

    public SavedView create(String ownerId, String name, String section, Map<String, String> query) {
        SavedView v = validate(ownerId, name, section, query);
        if (mongo.count(Query.query(Criteria.where("ownerId").is(ownerId)), SavedView.class, collection) >= MAX_PER_USER) {
            throw AnalyticsException.badRequest("Too many saved views");
        }
        return mongo.insert(v, collection);
    }

    public SavedView validate(String ownerId, String name, String section, Map<String, String> query) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw AnalyticsException.badRequest("name is required (max 100)");
        }
        if (section == null || section.isBlank() || section.length() > 40) {
            throw AnalyticsException.badRequest("section is required");
        }
        Map<String, String> q = new LinkedHashMap<>();
        if (query != null) {
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getKey().length() > 40 || e.getValue().length() > 500) {
                    continue;
                }
                if (q.size() >= 40) {
                    break;
                }
                q.put(e.getKey().replace('.', '_').replace('$', '_'), e.getValue());
            }
        }
        return new SavedView().setId(UUID.randomUUID().toString()).setOwnerId(ownerId).setName(name.trim()).setSection(section.trim())
                .setQuery(q).setCreatedAt(Instant.ofEpochMilli(clock.millis()));
    }

    public void delete(String ownerId, String id) {
        long n = mongo.remove(Query.query(Criteria.where("_id").is(id).and("ownerId").is(ownerId)), SavedView.class, collection).getDeletedCount();
        if (n == 0) {
            throw AnalyticsException.notFound("Saved view not found");
        }
    }
}
