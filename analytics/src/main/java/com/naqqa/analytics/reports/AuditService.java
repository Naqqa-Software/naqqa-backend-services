package com.naqqa.analytics.reports;

import com.naqqa.analytics.model.AuditEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class AuditService {

    public static final String VIEW = "VIEW";
    public static final String EXPORT = "EXPORT";
    public static final String IMPERSONATE = "IMPERSONATE";
    public static final String SCHEDULE = "SCHEDULE";
    public static final String SAVED_VIEW = "SAVED_VIEW";

    private final MongoTemplate mongo;
    private final String collection;
    private final Clock clock;

    public AuditService(MongoTemplate mongo, String collection, Clock clock) {
        this.mongo = mongo;
        this.collection = collection;
        this.clock = clock;
    }

    public void log(String userId, String action, String report, Collection<String> companyIds, String impersonating, Map<String, String> params) {
        if (mongo == null) {
            return;
        }
        try {
            Map<String, String> p = new LinkedHashMap<>();
            if (params != null) {
                params.forEach((k, v) -> {
                    if (k != null && v != null && p.size() < 30) {
                        p.put(k.replace('.', '_').replace('$', '_'), v.length() > 200 ? v.substring(0, 200) : v);
                    }
                });
            }
            AuditEntry e = new AuditEntry().setId(UUID.randomUUID().toString()).setTs(Instant.ofEpochMilli(clock.millis())).setUserId(userId)
                    .setAction(action).setReport(report).setCompanyIds(companyIds == null ? null : new ArrayList<>(companyIds))
                    .setImpersonating(impersonating).setParams(p);
            mongo.insert(e, collection);
        } catch (RuntimeException ex) {
            log.warn("[analytics] audit write failed: {}", ex.getMessage());
        }
    }

    public Page list(Instant from, Instant to, int page, int size) {
        Query q = Query.query(Criteria.where("ts").gte(from).lt(to));
        long total = mongo.count(q, AuditEntry.class, collection);
        q.with(Sort.by(Sort.Direction.DESC, "ts")).skip((long) page * size).limit(size);
        return new Page(total, mongo.find(q, AuditEntry.class, collection));
    }

    public record Page(long total, List<AuditEntry> items) {
    }
}
