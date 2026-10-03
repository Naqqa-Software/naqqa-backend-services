package com.naqqa.analytics.rollup;

import com.naqqa.analytics.collect.QualityCounters;
import com.naqqa.analytics.config.NaqqaAnalyticsProperties;
import com.naqqa.analytics.model.AnalyticsEvent;
import com.naqqa.analytics.model.RollupRow;
import com.naqqa.analytics.query.AnalyticsQuery;
import com.naqqa.analytics.query.EventSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class RollupJob {

    private final MongoTemplate mongo;
    private final EventSource source;
    private final NaqqaAnalyticsProperties properties;
    private final QualityCounters quality;
    private final Clock clock;
    private final ZoneId zone;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RollupJob(MongoTemplate mongo, EventSource source, NaqqaAnalyticsProperties properties, QualityCounters quality, Clock clock) {
        this.mongo = mongo;
        this.source = source;
        this.properties = properties;
        this.quality = quality;
        this.clock = clock;
        this.zone = ZoneId.of(properties.getTimezone());
    }

    public void today() {
        run(LocalDate.now(clock.withZone(zone)));
    }

    public void reconcileYesterday() {
        LocalDate yesterday = LocalDate.now(clock.withZone(zone)).minusDays(1);
        propagateSessionBots(yesterday);
        run(yesterday);
    }

    public void run(LocalDate day) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            AnalyticsQuery q = AnalyticsQuery.range(day, day, zone);
            List<AnalyticsEvent> events = source.events(q, null, null);
            List<RollupRow> rows = Rollups.daily(day.toString(), events, Instant.now(clock));
            replace(day.toString(), rows);
            quality.rolledUp(clock.millis());
        } catch (RuntimeException e) {
            log.warn("[analytics] rollup {} failed: {}", day, e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private void replace(String day, List<RollupRow> rows) {
        String coll = properties.getCollections().getDaily();
        mongo.remove(Query.query(Criteria.where("day").is(day)), coll);
        if (rows.isEmpty()) {
            return;
        }
        BulkOperations ops = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, RollupRow.class, coll);
        ops.insert(rows);
        ops.execute();
    }

    public void propagateSessionBots(LocalDate day) {
        try {
            Query q = Query.query(Criteria.where("day").is(day.toString()).and("bot").is(true));
            q.fields().include("_id");
            List<String> sids = new ArrayList<>();
            for (Map<?, ?> m : mongo.find(q, Map.class, properties.getCollections().getSession())) {
                Object id = m.get("_id");
                if (id != null) {
                    sids.add(String.valueOf(id));
                }
            }
            for (int i = 0; i < sids.size(); i += 500) {
                List<String> slice = sids.subList(i, Math.min(sids.size(), i + 500));
                mongo.updateMulti(Query.query(Criteria.where("sid").in(slice).and("bot").is(false)),
                        new Update().set("bot", true).set("botReason", "session"), properties.getCollections().getEvent());
            }
        } catch (RuntimeException e) {
            log.warn("[analytics] bot propagation failed: {}", e.getMessage());
        }
    }

    public List<RollupRow> load(LocalDate from, LocalDate to, String metric, Map<String, String> dims) {
        Criteria c = Criteria.where("day").gte(from.toString()).lte(to.toString()).and("metric").is(metric);
        if (dims != null) {
            dims.forEach((k, v) -> c.and("dims." + k).is(v));
        }
        return mongo.find(Query.query(c), RollupRow.class, properties.getCollections().getDaily());
    }
}
