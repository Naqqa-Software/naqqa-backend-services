package com.naqqa.analytics.service;

import com.naqqa.analytics.model.AnalyticsQuery;
import com.naqqa.analytics.model.AnalyticsResults.*;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.ArithmeticOperators;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.aggregation.DateOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.*;

/**
 * Read side of analytics — accurate on-demand aggregations over the raw {@code analytics_events}
 * collection: KPIs, timeseries (with distinct visitors/sessions), dimension breakdowns
 * (country/device/browser/os/source), top entities, and a realtime snapshot. Entity-agnostic:
 * every query is scoped by property + optional entityType/entityId.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsQueryService {

    private static final String COLL = "analytics_events";
    private final MongoTemplate mongo;

    // ── Dashboard payload ────────────────────────────────────────────────────────
    public Overview overview(AnalyticsQuery q) {
        Kpis kpis = kpis(q);
        return new Overview(
                kpis,
                series(q),
                dimension(q, "country", 12),
                dimension(q, "deviceType", 8),
                dimension(q, "browser", 8),
                dimension(q, "os", 8),
                dimension(q, "refMedium", 8),
                dimension(q, "refSource", 12),
                dimension(q, "language", 8),
                dimension(q, "path", 15),
                landingPages(q, 12),
                List.of(new Bucket("new", "New", kpis.newUsers(), kpis.newUsers()),
                        new Bucket("returning", "Returning", kpis.returningUsers(), kpis.returningUsers())),
                topEntities(q, 15));
    }

    public Kpis kpis(AnalyticsQuery q) {
        Aggregation agg = newAggregation(
                match(views(q)),
                group()
                        .count().as("views")
                        .addToSet("visitorId").as("visitors")
                        .addToSet("sessionId").as("sessions")
                        .addToSet("entityId").as("entities"),
                project("views")
                        .and("visitors").size().as("visitors")
                        .and("sessions").size().as("sessions")
                        .and("entities").size().as("entities"));
        Document d = one(agg);
        long views = num(d, "views");
        long visitors = num(d, "visitors");
        long sessions = num(d, "sessions");
        long entities = num(d, "entities");

        // Engagement (time-on-page) lives on engagement events carrying durationMs.
        Aggregation eng = newAggregation(
                match(base(q).and("durationMs").gt(0)),
                group().avg("durationMs").as("avg").count().as("engaged"));
        Document e = one(eng);
        double avg = e == null ? 0 : (e.get("avg") == null ? 0 : ((Number) e.get("avg")).doubleValue());
        long engaged = num(e, "engaged");

        long newUsers = distinctVisitors(new Criteria().andOperator(views(q), Criteria.where("newVisitor").is(true)));
        long returningUsers = Math.max(0, visitors - newUsers);

        double[] session = sessionStats(q); // [bounceRate, avgSessionDurationMs]
        double viewsPerSession = sessions > 0 ? round2((double) views / sessions) : 0;
        double engagementRate = views > 0 ? round2((double) engaged / views) : 0;
        return new Kpis(views, visitors, newUsers, returningUsers, sessions, engaged, round2(avg),
                round2(session[1]), viewsPerSession, engagementRate, round2(session[0]), entities);
    }

    /** Distinct unique users matching a criteria. */
    private long distinctVisitors(Criteria c) {
        Document d = one(newAggregation(match(c),
                group().addToSet("visitorId").as("v"),
                project().and("v").size().as("n")));
        return num(d, "n");
    }

    /** [bounceRate, avgSessionDurationMs] over the range. */
    private double[] sessionStats(AnalyticsQuery q) {
        // Bounce: sessions with a single page view.
        Document b = one(newAggregation(
                match(views(q)),
                group("sessionId").count().as("pv"),
                project().and(ConditionalOperators.when(Criteria.where("pv").lte(1)).then(1).otherwise(0)).as("bounced"),
                group().count().as("sessions").sum("bounced").as("bounced")));
        long sessions = num(b, "sessions");
        long bounced = num(b, "bounced");
        double bounceRate = sessions > 0 ? (double) bounced / sessions : 0;

        // Duration: last - first event per session (ms), averaged.
        Document dur = one(newAggregation(
                match(base(q)),
                group("sessionId").min("timestamp").as("first").max("timestamp").as("last"),
                project().and(ArithmeticOperators.Subtract.valueOf("last").subtract("first")).as("dur"),
                group().avg("dur").as("avgDur")));
        double avgDur = dur == null || dur.get("avgDur") == null ? 0 : ((Number) dur.get("avgDur")).doubleValue();
        return new double[]{bounceRate, avgDur};
    }

    /** Session entry (landing) pages. */
    public List<Bucket> landingPages(AnalyticsQuery q, int limit) {
        Aggregation agg = newAggregation(
                match(views(q)),
                sort(Sort.Direction.ASC, "timestamp"),
                group("sessionId").first("path").as("landing").first("visitorId").as("visitorId"),
                group("landing").count().as("views").addToSet("visitorId").as("visitors"),
                project("views").and("visitors").size().as("visitors").and("_id").as("key"),
                sort(Sort.Direction.DESC, "views"),
                limit(limit));
        List<Bucket> out = new ArrayList<>();
        for (Document d : list(agg)) {
            String key = d.get("key") == null ? "unknown" : String.valueOf(d.get("key"));
            out.add(new Bucket(key.isBlank() ? "unknown" : key, key, num(d, "views"), num(d, "visitors")));
        }
        return out;
    }

    /** Custom events (eventType = "event") grouped by name. */
    public List<EventStat> events(AnalyticsQuery q, int limit) {
        Aggregation agg = newAggregation(
                match(new Criteria().andOperator(base(q), Criteria.where("eventType").is("event"))),
                group("eventName").count().as("count").addToSet("visitorId").as("visitors"),
                project("count").and("visitors").size().as("visitors").and("_id").as("name"),
                sort(Sort.Direction.DESC, "count"),
                limit(limit));
        List<EventStat> out = new ArrayList<>();
        for (Document d : list(agg)) {
            String name = d.get("name") == null ? "(unnamed)" : String.valueOf(d.get("name"));
            out.add(new EventStat(name, num(d, "count"), num(d, "visitors")));
        }
        return out;
    }

    // ── Timeseries ───────────────────────────────────────────────────────────────
    public List<TimePoint> series(AnalyticsQuery q) {
        String fmt = format(q.granularity());
        Aggregation agg = newAggregation(
                match(views(q)),
                project()
                        .and(DateOperators.DateToString.dateOf("timestamp").toString(fmt)
                                .withTimezone(DateOperators.Timezone.valueOf("UTC"))).as("bucket")
                        .and("visitorId").as("visitorId")
                        .and("sessionId").as("sessionId"),
                group("bucket")
                        .count().as("views")
                        .addToSet("visitorId").as("visitors")
                        .addToSet("sessionId").as("sessions"),
                project("views")
                        .and("visitors").size().as("visitors")
                        .and("sessions").size().as("sessions")
                        .and("_id").as("bucket"),
                sort(Sort.Direction.ASC, "bucket"));
        List<TimePoint> out = new ArrayList<>();
        for (Document d : list(agg)) {
            out.add(new TimePoint(str(d, "bucket"), num(d, "views"), num(d, "visitors"), num(d, "sessions")));
        }
        return out;
    }

    // ── Dimension breakdown (country/device/browser/os/source…) ──────────────────
    public List<Bucket> dimension(AnalyticsQuery q, String field, int limit) {
        Aggregation agg = newAggregation(
                match(views(q)),
                group(field).count().as("views").addToSet("visitorId").as("visitors"),
                project("views").and("visitors").size().as("visitors").and("_id").as("key"),
                sort(Sort.Direction.DESC, "views"),
                limit(limit));
        List<Bucket> out = new ArrayList<>();
        for (Document d : list(agg)) {
            String key = d.get("key") == null ? "unknown" : String.valueOf(d.get("key"));
            if (key.isBlank()) {
                key = "unknown";
            }
            out.add(new Bucket(key, key, num(d, "views"), num(d, "visitors")));
        }
        return out;
    }

    // ── Top entities (blogs/courses…) ────────────────────────────────────────────
    public List<EntityStat> topEntities(AnalyticsQuery q, int limit) {
        Aggregation agg = newAggregation(
                match(views(q)),
                group("entityId")
                        .count().as("views")
                        .addToSet("visitorId").as("visitors")
                        .first("title").as("title")
                        .avg("durationMs").as("avgDur"),
                project("views").and("visitors").size().as("visitors")
                        .and("title").as("title").and("avgDur").as("avgDur").and("_id").as("entityId"),
                sort(Sort.Direction.DESC, "views"),
                limit(limit));
        List<EntityStat> out = new ArrayList<>();
        for (Document d : list(agg)) {
            String id = d.get("entityId") == null ? "(unknown)" : String.valueOf(d.get("entityId"));
            double avgDur = d.get("avgDur") == null ? 0 : ((Number) d.get("avgDur")).doubleValue();
            out.add(new EntityStat(id, str(d, "title"), num(d, "views"), num(d, "visitors"), round2(avgDur)));
        }
        return out;
    }

    // ── Realtime (last N minutes) ────────────────────────────────────────────────
    public Realtime realtime(String property, String entityType, int windowMinutes) {
        Instant since = Instant.now().minus(windowMinutes, ChronoUnit.MINUTES);
        Criteria c = Criteria.where("property").is(property).and("eventType").is("pageview")
                .and("timestamp").gte(since);
        if (entityType != null && !entityType.isBlank()) {
            c = c.and("entityType").is(entityType);
        }

        Document totals = one(newAggregation(match(c),
                group().count().as("views").addToSet("visitorId").as("visitors"),
                project("views").and("visitors").size().as("visitors")));
        long views = num(totals, "views");
        long active = num(totals, "visitors");

        List<Bucket> topPaths = simpleTop(c, "path", 8);
        List<Bucket> byCountry = simpleTop(c, "country", 8);

        Aggregation perMin = newAggregation(match(c),
                project().and(DateOperators.DateToString.dateOf("timestamp").toString("%Y-%m-%d %H:%M")
                        .withTimezone(DateOperators.Timezone.valueOf("UTC"))).as("bucket")
                        .and("visitorId").as("visitorId"),
                group("bucket").count().as("views").addToSet("visitorId").as("visitors"),
                project("views").and("visitors").size().as("visitors").and("_id").as("bucket"),
                sort(Sort.Direction.ASC, "bucket"));
        List<TimePoint> perMinute = new ArrayList<>();
        for (Document d : list(perMin)) {
            perMinute.add(new TimePoint(str(d, "bucket"), num(d, "views"), num(d, "visitors"), 0));
        }
        return new Realtime(active, views, topPaths, byCountry, perMinute);
    }

    private List<Bucket> simpleTop(Criteria c, String field, int limit) {
        Aggregation agg = newAggregation(match(c),
                group(field).count().as("views").addToSet("visitorId").as("visitors"),
                project("views").and("visitors").size().as("visitors").and("_id").as("key"),
                sort(Sort.Direction.DESC, "views"), limit(limit));
        List<Bucket> out = new ArrayList<>();
        for (Document d : list(agg)) {
            String key = d.get("key") == null ? "unknown" : String.valueOf(d.get("key"));
            out.add(new Bucket(key.isBlank() ? "unknown" : key, key, num(d, "views"), num(d, "visitors")));
        }
        return out;
    }

    // ── Discovery (for FE filters) ────────────────────────────────────────────────
    /** Distinct properties (sites) that have any traffic. */
    public List<String> properties() {
        return mongo.findDistinct(new Query(), "property", COLL, String.class);
    }

    /** Distinct entity types seen for a property (e.g. "blog", "course"). */
    public List<String> entityTypes(String property) {
        Query q = property == null || property.isBlank() ? new Query()
                : Query.query(Criteria.where("property").is(property));
        return mongo.findDistinct(q, "entityType", COLL, String.class);
    }

    // ── Criteria helpers ──────────────────────────────────────────────────────────
    private Criteria base(AnalyticsQuery q) {
        Criteria c = Criteria.where("property").is(q.property());
        if (q.entityType() != null && !q.entityType().isBlank()) {
            c = c.and("entityType").is(q.entityType());
        }
        if (q.entityId() != null && !q.entityId().isBlank()) {
            c = c.and("entityId").is(q.entityId());
        }
        if (q.path() != null && !q.path().isBlank()) {
            c = c.and("path").is(q.path());
        }
        if (q.from() != null || q.to() != null) {
            Criteria ts = Criteria.where("timestamp");
            if (q.from() != null) {
                ts = ts.gte(q.from());
            }
            if (q.to() != null) {
                ts = ts.lt(q.to());
            }
            c = new Criteria().andOperator(c, ts);
        }
        return c;
    }

    /** Base scope restricted to actual page views (excludes engagement heartbeats). */
    private Criteria views(AnalyticsQuery q) {
        return new Criteria().andOperator(base(q), Criteria.where("eventType").is("pageview"));
    }

    private String format(AnalyticsQuery.Granularity g) {
        if (g == null) {
            return "%Y-%m-%d";
        }
        return switch (g) {
            case HOUR -> "%Y-%m-%d %H:00";
            case MONTH -> "%Y-%m";
            case WEEK -> "%G-W%V";
            default -> "%Y-%m-%d";
        };
    }

    // ── Mongo plumbing ─────────────────────────────────────────────────────────────
    private List<Document> list(Aggregation agg) {
        AggregationResults<Document> r = mongo.aggregate(agg, COLL, Document.class);
        return r.getMappedResults();
    }

    private Document one(Aggregation agg) {
        List<Document> r = list(agg);
        return r.isEmpty() ? null : r.get(0);
    }

    private long num(Document d, String k) {
        if (d == null || d.get(k) == null) {
            return 0;
        }
        return ((Number) d.get(k)).longValue();
    }

    private String str(Document d, String k) {
        return d == null || d.get(k) == null ? null : String.valueOf(d.get(k));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
