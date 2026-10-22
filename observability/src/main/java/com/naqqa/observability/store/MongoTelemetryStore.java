package com.naqqa.observability.store;

import com.naqqa.observability.spi.TelemetryStore;
import com.naqqa.observability.telemetry.ObsProjectConfig.Retention;
import com.naqqa.observability.telemetry.Telemetry.ErrorEvent;
import com.naqqa.observability.telemetry.Telemetry.Frame;
import com.naqqa.observability.telemetry.Telemetry.IssueQuery;
import com.naqqa.observability.telemetry.Telemetry.IssueState;
import com.naqqa.observability.telemetry.Telemetry.LogQuery;
import com.naqqa.observability.telemetry.Telemetry.LogRecord;
import com.naqqa.observability.telemetry.Telemetry.MetricPoint;
import com.naqqa.observability.telemetry.Telemetry.Page;
import com.naqqa.observability.telemetry.Telemetry.SpanRecord;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

public class MongoTelemetryStore implements TelemetryStore {

    static final long DAY_MS = 24L * 3600 * 1000;
    static final int MAX_COUNT = 100_000;

    private final MongoOperations mongo;
    private final Function<String, Retention> retention;
    private final String logs;
    private final String points;
    private final String spans;
    private final String events;
    private final String issues;

    public MongoTelemetryStore(MongoOperations mongo, String prefix, Function<String, Retention> retention) {
        this.mongo = mongo;
        this.retention = retention;
        String p = prefix == null ? "" : prefix;
        this.logs = p + "logs";
        this.points = p + "metric_points";
        this.spans = p + "spans";
        this.events = p + "error_events";
        this.issues = p + "issues";
    }

    @SuppressWarnings("deprecation")
    public void ensureIndexes() {
        for (String c : List.of(logs, points, spans, events, issues)) {
            mongo.indexOps(c).ensureIndex(new Index().on("expireAt", Sort.Direction.ASC).expire(Duration.ZERO));
        }
        mongo.indexOps(logs).ensureIndex(new Index().on("projectId", Sort.Direction.ASC).on("tsNs", Sort.Direction.DESC).on("id", Sort.Direction.DESC));
        mongo.indexOps(logs).ensureIndex(new Index().on("projectId", Sort.Direction.ASC).on("traceId", Sort.Direction.ASC));
        mongo.indexOps(points).ensureIndex(new Index().on("projectId", Sort.Direction.ASC).on("name", Sort.Direction.ASC).on("tsMs", Sort.Direction.ASC));
        mongo.indexOps(spans).ensureIndex(new Index().on("projectId", Sort.Direction.ASC).on("traceId", Sort.Direction.ASC));
        mongo.indexOps(spans).ensureIndex(new Index().on("projectId", Sort.Direction.ASC).on("startMs", Sort.Direction.DESC));
        mongo.indexOps(events).ensureIndex(new Index().on("projectId", Sort.Direction.ASC).on("fingerprint", Sort.Direction.ASC).on("tsMs", Sort.Direction.DESC));
        mongo.indexOps(issues).ensureIndex(new Index().on("projectId", Sort.Direction.ASC).on("fingerprint", Sort.Direction.ASC).unique());
        mongo.indexOps(issues).ensureIndex(new Index().on("projectId", Sort.Direction.ASC).on("lastSeenMs", Sort.Direction.DESC));
    }

    private Date expire(String projectId, long fromMs, int days) {
        return new Date(fromMs + days * DAY_MS);
    }

    private Retention retentionOf(String projectId) {
        Retention r = retention == null ? null : retention.apply(projectId);
        return r == null ? Retention.DEFAULT : r;
    }

    @Override
    public void appendLogs(List<LogRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        List<Document> docs = new ArrayList<>(records.size());
        for (LogRecord r : records) {
            long tsMs = r.tsNs() / 1_000_000L;
            docs.add(new Document("projectId", r.projectId()).append("id", r.id()).append("tsNs", r.tsNs()).append("tsMs", tsMs)
                    .append("level", r.level()).append("service", r.service()).append("message", r.message())
                    .append("traceId", r.traceId()).append("spanId", r.spanId())
                    .append("attributes", kv(r.attributes())).append("resource", kv(r.resource()))
                    .append("expireAt", expire(r.projectId(), tsMs, retentionOf(r.projectId()).logsDays())));
        }
        mongo.insert(docs, logs);
    }

    private Criteria logCriteria(LogQuery q) {
        Criteria c = Criteria.where("projectId").is(q.projectId());
        if (q.levels() != null && !q.levels().isEmpty()) {
            c = c.and("level").in(q.levels());
        }
        if (q.services() != null && !q.services().isEmpty()) {
            c = c.and("service").in(q.services());
        }
        if (q.traceId() != null && !q.traceId().isBlank()) {
            c = c.and("traceId").is(q.traceId());
        }
        if (q.fromMs() != null || q.toMs() != null) {
            Criteria ts = c.and("tsMs");
            if (q.fromMs() != null) {
                ts = ts.gte(q.fromMs());
            }
            if (q.toMs() != null) {
                ts = ts.lte(q.toMs());
            }
            c = ts;
        }
        if (q.search() != null && !q.search().isBlank()) {
            Pattern rx = Pattern.compile(Pattern.quote(q.search().trim()), Pattern.CASE_INSENSITIVE);
            c = c.andOperator(new Criteria().orOperator(Criteria.where("message").regex(rx), Criteria.where("service").regex(rx),
                    Criteria.where("traceId").regex(rx), Criteria.where("attributes.v").regex(rx)));
        }
        return c;
    }

    @Override
    public Page<LogRecord> queryLogs(LogQuery q) {
        Criteria base = logCriteria(q);
        long total = mongo.count(new Query(base).limit(MAX_COUNT), logs);
        Query query = new Query(base);
        Cursor cursor = Cursor.decode(q.cursor());
        if (cursor != null) {
            query.addCriteria(new Criteria().orOperator(Criteria.where("tsNs").lt(cursor.key()),
                    new Criteria().andOperator(Criteria.where("tsNs").is(cursor.key()), Criteria.where("id").lt(cursor.id()))));
        }
        query.with(Sort.by(Sort.Order.desc("tsNs"), Sort.Order.desc("id"))).limit(Math.max(1, q.limit()) + 1);
        List<Document> docs = mongo.find(query, Document.class, logs);
        boolean more = docs.size() > q.limit();
        List<LogRecord> out = new ArrayList<>();
        for (Document d : more ? docs.subList(0, q.limit()) : docs) {
            out.add(toLog(d));
        }
        String next = more && !out.isEmpty() ? Cursor.encode(out.get(out.size() - 1).tsNs(), out.get(out.size() - 1).id()) : null;
        return new Page<>(out, (int) Math.min(Integer.MAX_VALUE, total), next);
    }

    @Override
    public Map<String, Map<String, Long>> logFacets(LogQuery q, Set<String> fields) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        for (String field : fields) {
            if (!"service".equals(field) && !"level".equals(field)) {
                out.put(field, new LinkedHashMap<>());
                continue;
            }
            Aggregation agg = Aggregation.newAggregation(Aggregation.match(logCriteria(q)), Aggregation.group(field).count().as("count"),
                    Aggregation.sort(Sort.Direction.DESC, "count"), Aggregation.limit(50));
            AggregationResults<Document> res = mongo.aggregate(agg, logs, Document.class);
            Map<String, Long> buckets = new LinkedHashMap<>();
            for (Document d : res.getMappedResults()) {
                Object key = d.get("_id");
                if (key != null) {
                    buckets.put(String.valueOf(key), ((Number) d.get("count")).longValue());
                }
            }
            out.put(field, buckets);
        }
        return out;
    }

    @Override
    public void appendPoints(List<MetricPoint> list) {
        if (list.isEmpty()) {
            return;
        }
        List<Document> docs = new ArrayList<>(list.size());
        for (MetricPoint p : list) {
            docs.add(new Document("projectId", p.projectId()).append("name", p.name()).append("unit", p.unit())
                    .append("labels", kv(p.labels())).append("tsMs", p.tsMs()).append("value", p.value())
                    .append("expireAt", expire(p.projectId(), p.tsMs(), retentionOf(p.projectId()).metricsDays())));
        }
        mongo.insert(docs, points);
    }

    @Override
    public List<MetricPoint> queryPoints(String projectId, Set<String> names, long fromMs, long toMs) {
        Criteria c = Criteria.where("projectId").is(projectId).and("tsMs").gte(fromMs).lte(toMs);
        if (names != null && !names.isEmpty()) {
            c = c.and("name").in(names);
        }
        Query q = new Query(c).with(Sort.by(Sort.Direction.ASC, "tsMs")).limit(MAX_COUNT);
        List<MetricPoint> out = new ArrayList<>();
        for (Document d : mongo.find(q, Document.class, points)) {
            out.add(new MetricPoint(d.getString("projectId"), d.getString("name"), d.getString("unit"), map(d.get("labels")),
                    num(d.get("tsMs")), ((Number) d.get("value")).doubleValue()));
        }
        return out;
    }

    @Override
    public void appendSpans(List<SpanRecord> list) {
        if (list.isEmpty()) {
            return;
        }
        List<Document> docs = new ArrayList<>(list.size());
        for (SpanRecord s : list) {
            long startMs = s.startNs() / 1_000_000L;
            docs.add(new Document("projectId", s.projectId()).append("traceId", s.traceId()).append("spanId", s.spanId())
                    .append("parentSpanId", s.parentSpanId()).append("service", s.service()).append("name", s.name())
                    .append("kind", s.kind()).append("startNs", s.startNs()).append("endNs", s.endNs()).append("startMs", startMs)
                    .append("status", s.status()).append("attributes", kv(s.attributes()))
                    .append("expireAt", expire(s.projectId(), startMs, retentionOf(s.projectId()).tracesDays())));
        }
        mongo.insert(docs, spans);
    }

    @Override
    public List<SpanRecord> trace(String projectId, String traceId) {
        if (traceId == null) {
            return List.of();
        }
        Query q = new Query(Criteria.where("projectId").is(projectId).and("traceId").is(traceId)).limit(5000);
        return mongo.find(q, Document.class, spans).stream().map(MongoTelemetryStore::toSpan).toList();
    }

    @Override
    public List<SpanRecord> rootSpans(String projectId, long fromMs, long toMs, int limit) {
        Query q = new Query(Criteria.where("projectId").is(projectId).and("parentSpanId").is(null).and("startMs").gte(fromMs).lte(toMs))
                .with(Sort.by(Sort.Direction.DESC, "startMs")).limit(Math.max(1, limit));
        return mongo.find(q, Document.class, spans).stream().map(MongoTelemetryStore::toSpan).toList();
    }

    @Override
    public List<SpanRecord> spans(String projectId, long fromMs, long toMs, int limit) {
        Query q = new Query(Criteria.where("projectId").is(projectId).and("startMs").gte(fromMs).lte(toMs))
                .with(Sort.by(Sort.Direction.DESC, "startMs")).limit(Math.max(1, limit));
        return mongo.find(q, Document.class, spans).stream().map(MongoTelemetryStore::toSpan).toList();
    }

    @Override
    public void appendErrorEvent(ErrorEvent e) {
        mongo.insert(new Document("projectId", e.projectId()).append("id", e.id()).append("tsMs", e.tsMs()).append("type", e.type())
                .append("message", e.message()).append("level", e.level()).append("release", e.release()).append("userId", e.userId())
                .append("service", e.service()).append("traceId", e.traceId()).append("frames", frames(e.frames()))
                .append("tags", kv(e.tags())).append("fingerprint", e.fingerprint())
                .append("expireAt", expire(e.projectId(), e.tsMs(), retentionOf(e.projectId()).issuesDays())), events);
    }

    @Override
    public List<ErrorEvent> errorEvents(String projectId, String fingerprint, int limit) {
        Query q = new Query(Criteria.where("projectId").is(projectId).and("fingerprint").is(fingerprint))
                .with(Sort.by(Sort.Direction.DESC, "tsMs")).limit(Math.max(1, limit));
        List<ErrorEvent> out = new ArrayList<>();
        for (Document d : mongo.find(q, Document.class, events)) {
            out.add(new ErrorEvent(d.getString("projectId"), d.getString("id"), num(d.get("tsMs")), d.getString("type"),
                    d.getString("message"), d.getString("level"), d.getString("release"), d.getString("userId"),
                    d.getString("service"), d.getString("traceId"), toFrames(d.get("frames")), map(d.get("tags")), d.getString("fingerprint")));
        }
        return out;
    }

    @Override
    public Optional<IssueState> issueByFingerprint(String projectId, String fingerprint) {
        Document d = mongo.findOne(new Query(Criteria.where("projectId").is(projectId).and("fingerprint").is(fingerprint)), Document.class, issues);
        return Optional.ofNullable(d).map(MongoTelemetryStore::toIssue);
    }

    @Override
    public Optional<IssueState> issue(String projectId, String issueId) {
        Document d = mongo.findOne(new Query(Criteria.where("_id").is(issueId).and("projectId").is(projectId)), Document.class, issues);
        return Optional.ofNullable(d).map(MongoTelemetryStore::toIssue);
    }

    @Override
    public void saveIssue(IssueState i) {
        List<Document> tags = new ArrayList<>();
        i.tagCounts().forEach((k, values) -> {
            List<Document> vs = new ArrayList<>();
            values.forEach((v, c) -> vs.add(new Document("v", v).append("c", c)));
            tags.add(new Document("k", k).append("values", vs));
        });
        Document doc = new Document("_id", i.id()).append("projectId", i.projectId()).append("fingerprint", i.fingerprint())
                .append("type", i.type()).append("message", i.message()).append("culprit", i.culprit()).append("level", i.level())
                .append("status", i.status()).append("count", i.count()).append("users", new ArrayList<>(i.users()))
                .append("firstSeenMs", i.firstSeenMs()).append("lastSeenMs", i.lastSeenMs()).append("firstRelease", i.firstRelease())
                .append("lastRelease", i.lastRelease()).append("assignee", i.assignee()).append("stack", frames(i.stack()))
                .append("tags", tags).append("traceIds", i.traceIds())
                .append("expireAt", expire(i.projectId(), i.lastSeenMs(), retentionOf(i.projectId()).issuesDays()));
        mongo.save(doc, issues);
    }

    @Override
    public Page<IssueState> queryIssues(IssueQuery q) {
        Criteria c = Criteria.where("projectId").is(q.projectId());
        if (q.statuses() != null && !q.statuses().isEmpty()) {
            c = c.and("status").in(q.statuses());
        }
        if (q.levels() != null && !q.levels().isEmpty()) {
            c = c.and("level").in(q.levels());
        }
        if (q.search() != null && !q.search().isBlank()) {
            Pattern rx = Pattern.compile(Pattern.quote(q.search().trim()), Pattern.CASE_INSENSITIVE);
            c = c.andOperator(new Criteria().orOperator(Criteria.where("type").regex(rx), Criteria.where("message").regex(rx),
                    Criteria.where("culprit").regex(rx)));
        }
        long total = mongo.count(new Query(c).limit(MAX_COUNT), issues);
        Query query = new Query(c);
        Cursor cursor = Cursor.decode(q.cursor());
        if (cursor != null) {
            query.addCriteria(new Criteria().orOperator(Criteria.where("lastSeenMs").lt(cursor.key()),
                    new Criteria().andOperator(Criteria.where("lastSeenMs").is(cursor.key()), Criteria.where("_id").lt(cursor.id()))));
        }
        query.with(Sort.by(Sort.Order.desc("lastSeenMs"), Sort.Order.desc("_id"))).limit(Math.max(1, q.limit()) + 1);
        List<Document> docs = mongo.find(query, Document.class, issues);
        boolean more = docs.size() > q.limit();
        List<IssueState> out = new ArrayList<>();
        for (Document d : more ? docs.subList(0, q.limit()) : docs) {
            out.add(toIssue(d));
        }
        String next = more && !out.isEmpty() ? Cursor.encode(out.get(out.size() - 1).lastSeenMs(), out.get(out.size() - 1).id()) : null;
        return new Page<>(out, (int) Math.min(Integer.MAX_VALUE, total), next);
    }

    @Override
    public boolean hasData(String projectId) {
        Query q = new Query(Criteria.where("projectId").is(projectId));
        return mongo.exists(q, logs) || mongo.exists(q, issues) || mongo.exists(q, spans) || mongo.exists(q, points);
    }

    @Override
    public void purge(String projectId, long logsBeforeMs, long pointsBeforeMs, long spansBeforeMs, long issuesBeforeMs) {
        mongo.remove(new Query(Criteria.where("projectId").is(projectId).and("tsMs").lt(logsBeforeMs)), logs);
        mongo.remove(new Query(Criteria.where("projectId").is(projectId).and("tsMs").lt(pointsBeforeMs)), points);
        mongo.remove(new Query(Criteria.where("projectId").is(projectId).and("startMs").lt(spansBeforeMs)), spans);
        mongo.remove(new Query(Criteria.where("projectId").is(projectId).and("lastSeenMs").lt(issuesBeforeMs)), issues);
        mongo.remove(new Query(Criteria.where("projectId").is(projectId).and("tsMs").lt(issuesBeforeMs)), events);
    }

    static List<Document> kv(Map<String, String> values) {
        List<Document> out = new ArrayList<>();
        if (values != null) {
            values.forEach((k, v) -> out.add(new Document("k", k).append("v", v)));
        }
        return out;
    }

    static Map<String, String> map(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw instanceof Collection<?> list) {
            for (Object o : list) {
                if (o instanceof Document d && d.getString("k") != null) {
                    out.put(d.getString("k"), d.getString("v"));
                }
            }
        }
        return out;
    }

    static List<Document> frames(List<Frame> frames) {
        List<Document> out = new ArrayList<>();
        for (Frame f : frames) {
            out.add(new Document("function", f.function()).append("filename", f.filename()).append("lineNo", f.lineNo())
                    .append("colNo", f.colNo()).append("inApp", f.inApp()));
        }
        return out;
    }

    static List<Frame> toFrames(Object raw) {
        List<Frame> out = new ArrayList<>();
        if (raw instanceof Collection<?> list) {
            for (Object o : list) {
                if (o instanceof Document d) {
                    out.add(new Frame(d.getString("function"), d.getString("filename"), (int) num(d.get("lineNo")),
                            (int) num(d.get("colNo")), Boolean.TRUE.equals(d.get("inApp"))));
                }
            }
        }
        return out;
    }

    static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    static LogRecord toLog(Document d) {
        return new LogRecord(d.getString("projectId"), d.getString("id"), num(d.get("tsNs")), d.getString("level"),
                d.getString("service"), d.getString("message"), d.getString("traceId"), d.getString("spanId"),
                map(d.get("attributes")), map(d.get("resource")));
    }

    static SpanRecord toSpan(Document d) {
        return new SpanRecord(d.getString("projectId"), d.getString("traceId"), d.getString("spanId"), d.getString("parentSpanId"),
                d.getString("service"), d.getString("name"), d.getString("kind"), num(d.get("startNs")), num(d.get("endNs")),
                d.getString("status"), map(d.get("attributes")));
    }

    @SuppressWarnings("unchecked")
    static IssueState toIssue(Document d) {
        Map<String, Map<String, Long>> tags = new LinkedHashMap<>();
        Object rawTags = d.get("tags");
        if (rawTags instanceof Collection<?> list) {
            for (Object o : list) {
                if (o instanceof Document t) {
                    Map<String, Long> values = new LinkedHashMap<>();
                    Object rawValues = t.get("values");
                    if (rawValues instanceof Collection<?> vs) {
                        for (Object v : vs) {
                            if (v instanceof Document vd) {
                                values.put(vd.getString("v"), num(vd.get("c")));
                            }
                        }
                    }
                    tags.put(t.getString("k"), values);
                }
            }
        }
        Object users = d.get("users");
        Object traces = d.get("traceIds");
        return new IssueState(d.getString("projectId"), d.getString("_id"), d.getString("fingerprint"), d.getString("type"),
                d.getString("message"), d.getString("culprit"), d.getString("level"), d.getString("status"), num(d.get("count")),
                users instanceof Collection<?> u ? new HashSet<>((Collection<String>) u) : new HashSet<>(),
                num(d.get("firstSeenMs")), num(d.get("lastSeenMs")), d.getString("firstRelease"), d.getString("lastRelease"),
                d.getString("assignee"), toFrames(d.get("stack")), tags,
                traces instanceof Collection<?> t ? new ArrayList<>((Collection<String>) t) : new ArrayList<>());
    }
}
