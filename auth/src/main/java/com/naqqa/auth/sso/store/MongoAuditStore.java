package com.naqqa.auth.sso.store;

import com.naqqa.sso.audit.AuditEntry;
import com.naqqa.sso.audit.AuditEvent;
import com.naqqa.sso.audit.AuditStore;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MongoAuditStore implements AuditStore {

    public static final String COLLECTION = "sso_audit";

    private final MongoTemplate template;

    public MongoAuditStore(MongoTemplate template) {
        this.template = template;
    }

    @Override
    public void append(AuditEntry e) {
        AuditEvent ev = e.event();
        List<Document> attributes = new ArrayList<>();
        for (Map.Entry<String, String> a : ev.attributes().entrySet()) {
            attributes.add(new Document("k", a.getKey()).append("v", a.getValue()));
        }
        Document doc = new Document("_id", e.seq())
                .append("time", MongoSupport.date(ev.time()))
                .append("tenantId", ev.tenantId())
                .append("actor", ev.actor())
                .append("action", ev.action())
                .append("target", ev.target())
                .append("outcome", ev.outcome())
                .append("ip", ev.ip())
                .append("attributes", attributes)
                .append("prevHash", e.prevHash())
                .append("hash", e.hash())
                .append("keyId", e.keyId())
                .append("signature", e.signature());
        template.insert(doc, COLLECTION);
    }

    static AuditEntry fromDocument(Document d) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Document a : d.getList("attributes", Document.class, List.of())) {
            attributes.put(a.getString("k"), a.getString("v"));
        }
        AuditEvent ev = new AuditEvent(MongoSupport.instant(d.getDate("time")), d.getString("tenantId"), d.getString("actor"),
                d.getString("action"), d.getString("target"), d.getString("outcome"), d.getString("ip"), attributes);
        return new AuditEntry(MongoSupport.number(d, "_id"), ev, d.getString("prevHash"), d.getString("hash"),
                d.getString("keyId"), d.getString("signature"));
    }

    @Override
    public List<AuditEntry> all() {
        List<AuditEntry> out = new ArrayList<>();
        for (Document d : template.find(new Query().with(Sort.by(Sort.Direction.ASC, "_id")), Document.class, COLLECTION)) {
            out.add(fromDocument(d));
        }
        return out;
    }

    public Optional<AuditEntry> last() {
        Document d = template.findOne(new Query().with(Sort.by(Sort.Direction.DESC, "_id")).limit(1), Document.class, COLLECTION);
        return Optional.ofNullable(d).map(MongoAuditStore::fromDocument);
    }

    public Optional<AuditEntry> find(long seq) {
        return Optional.ofNullable(template.findById(seq, Document.class, COLLECTION)).map(MongoAuditStore::fromDocument);
    }

    public List<AuditEntry> range(long fromSeq, int limit) {
        List<AuditEntry> out = new ArrayList<>();
        Query query = new Query(Criteria.where("_id").gte(fromSeq)).with(Sort.by(Sort.Direction.ASC, "_id")).limit(limit);
        for (Document d : template.find(query, Document.class, COLLECTION)) {
            out.add(fromDocument(d));
        }
        return out;
    }

    public Optional<AuditEntry> firstAtOrAfter(java.time.Instant cutoff) {
        Query query = new Query(Criteria.where("time").gte(MongoSupport.date(cutoff))).with(Sort.by(Sort.Direction.ASC, "_id")).limit(1);
        return Optional.ofNullable(template.findOne(query, Document.class, COLLECTION)).map(MongoAuditStore::fromDocument);
    }

    public long deleteUpTo(long seq) {
        return template.remove(new Query(Criteria.where("_id").lte(seq)), COLLECTION).getDeletedCount();
    }
}
