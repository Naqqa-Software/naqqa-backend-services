package com.naqqa.analytics.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

import java.time.Duration;

@Slf4j
public class AnalyticsMongoIndexes {

    private final MongoTemplate mongo;
    private final NaqqaAnalyticsProperties properties;

    public AnalyticsMongoIndexes(MongoTemplate mongo, NaqqaAnalyticsProperties properties) {
        this.mongo = mongo;
        this.properties = properties;
    }

    public void ensure() {
        NaqqaAnalyticsProperties.Collections c = properties.getCollections();
        Duration raw = Duration.ofDays(Math.max(1, properties.getRawRetentionDays()));
        ensure(c.getEvent(), new Index().on("ts", Sort.Direction.ASC).named("an_ts_ttl").expire(raw));
        ensure(c.getEvent(), new Index().on("companyId", Sort.Direction.ASC).on("ts", Sort.Direction.ASC).named("an_company_ts"));
        ensure(c.getEvent(), new Index().on("entityType", Sort.Direction.ASC).on("entityId", Sort.Direction.ASC).on("ts", Sort.Direction.ASC)
                .named("an_entity_ts"));
        ensure(c.getEvent(), new Index().on("name", Sort.Direction.ASC).on("ts", Sort.Direction.ASC).named("an_name_ts"));
        ensure(c.getEvent(), new Index().on("sid", Sort.Direction.ASC).named("an_sid"));
        ensure(c.getSession(), new Index().on("clientSid", Sort.Direction.ASC).on("end", Sort.Direction.DESC).named("an_client_sid"));
        ensure(c.getSession(), new Index().on("day", Sort.Direction.ASC).on("bot", Sort.Direction.ASC).named("an_day_bot"));
        ensure(c.getSession(), new Index().on("end", Sort.Direction.ASC).named("an_end_ttl").expire(raw));
        ensure(c.getVisitor(), new Index().on("last", Sort.Direction.ASC).named("an_last_ttl").expire(raw));
        ensure(c.getDaily(), new Index().on("day", Sort.Direction.ASC).on("metric", Sort.Direction.ASC).named("an_day_metric"));
        ensure(c.getAudit(), new Index().on("ts", Sort.Direction.DESC).named("an_audit_ts"));
        ensure(c.getAudit(), new Index().on("userId", Sort.Direction.ASC).on("ts", Sort.Direction.DESC).named("an_audit_user"));
        ensure(c.getSavedView(), new Index().on("ownerId", Sort.Direction.ASC).named("an_view_owner"));
        ensure(c.getScheduledReport(), new Index().on("ownerId", Sort.Direction.ASC).named("an_report_owner"));
        ensure(c.getScheduledReport(), new Index().on("nextRunAt", Sort.Direction.ASC).named("an_report_next"));
    }

    private void ensure(String collection, Index index) {
        try {
            mongo.indexOps(collection).ensureIndex(index);
        } catch (RuntimeException e) {
            log.warn("[analytics] index on {} failed: {}", collection, e.getMessage());
        }
    }
}
