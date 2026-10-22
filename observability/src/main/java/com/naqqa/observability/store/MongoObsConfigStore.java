package com.naqqa.observability.store;

import com.naqqa.observability.spi.ObsConfigStore;
import com.naqqa.observability.telemetry.ObsProjectConfig;
import com.naqqa.observability.telemetry.ObsProjectConfig.Retention;
import com.naqqa.observability.telemetry.ObsProjectConfig.TokenMeta;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;

public class MongoObsConfigStore implements ObsConfigStore {

    private final MongoOperations mongo;
    private final String collection;

    public MongoObsConfigStore(MongoOperations mongo, String prefix) {
        this.mongo = mongo;
        this.collection = (prefix == null ? "" : prefix) + "configs";
    }

    @SuppressWarnings("deprecation")
    public void ensureIndexes() {
        mongo.indexOps(collection).ensureIndex(new Index().on("ingest.id", Sort.Direction.ASC));
        mongo.indexOps(collection).ensureIndex(new Index().on("management.id", Sort.Direction.ASC));
    }

    @Override
    public Optional<ObsProjectConfig> find(String projectId) {
        if (projectId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongo.findOne(new Query(Criteria.where("_id").is(projectId)), Document.class, collection))
                .map(MongoObsConfigStore::toConfig);
    }

    @Override
    public Optional<ObsProjectConfig> findByTokenId(String tokenId) {
        if (tokenId == null) {
            return Optional.empty();
        }
        Query q = new Query(new Criteria().orOperator(Criteria.where("ingest.id").is(tokenId), Criteria.where("management.id").is(tokenId)));
        return Optional.ofNullable(mongo.findOne(q, Document.class, collection)).map(MongoObsConfigStore::toConfig);
    }

    @Override
    public void save(ObsProjectConfig c) {
        Document doc = new Document("_id", c.projectId()).append("env", c.env()).append("ingest", token(c.ingest()))
                .append("management", token(c.management())).append("origins", c.originAllowlist())
                .append("retention", new Document("logsDays", c.retention().logsDays()).append("metricsDays", c.retention().metricsDays())
                        .append("tracesDays", c.retention().tracesDays()).append("issuesDays", c.retention().issuesDays()))
                .append("ingestEnabled", c.ingestEnabled());
        mongo.save(doc, collection);
    }

    @Override
    public Collection<ObsProjectConfig> all() {
        List<ObsProjectConfig> out = new ArrayList<>();
        for (Document d : mongo.findAll(Document.class, collection)) {
            out.add(toConfig(d));
        }
        return out;
    }

    private static Document token(TokenMeta t) {
        if (t == null) {
            return null;
        }
        return new Document("id", t.id()).append("kind", t.kind()).append("env", t.env()).append("hash", t.hash())
                .append("masked", t.masked()).append("scopes", t.scopes()).append("createdAt", date(t.createdAt()))
                .append("lastRotatedAt", date(t.lastRotatedAt())).append("lastUsedAt", date(t.lastUsedAt()));
    }

    @SuppressWarnings("unchecked")
    private static TokenMeta toToken(Object raw) {
        if (!(raw instanceof Document d)) {
            return null;
        }
        return new TokenMeta(d.getString("id"), d.getString("kind"), d.getString("env"), d.getString("hash"), d.getString("masked"),
                (List<String>) d.get("scopes"), instant(d.getDate("createdAt")), instant(d.getDate("lastRotatedAt")),
                instant(d.getDate("lastUsedAt")));
    }

    @SuppressWarnings("unchecked")
    private static ObsProjectConfig toConfig(Document d) {
        Document r = d.get("retention", Document.class);
        Retention retention = r == null ? Retention.DEFAULT : new Retention(r.getInteger("logsDays", 30), r.getInteger("metricsDays", 90),
                r.getInteger("tracesDays", 7), r.getInteger("issuesDays", 180));
        Object origins = d.get("origins");
        return new ObsProjectConfig(d.getString("_id"), d.getString("env"), toToken(d.get("ingest")), toToken(d.get("management")),
                origins instanceof List<?> l ? (List<String>) l : List.of(), retention, Boolean.TRUE.equals(d.get("ingestEnabled")));
    }

    private static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
