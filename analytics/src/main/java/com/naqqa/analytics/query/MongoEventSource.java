package com.naqqa.analytics.query;

import com.naqqa.analytics.model.AnalyticsEvent;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MongoEventSource implements EventSource {

    private final MongoTemplate mongo;
    private final String collection;
    private final long maxScan;

    public MongoEventSource(MongoTemplate mongo, String collection, long maxScan) {
        this.mongo = mongo;
        this.collection = collection;
        this.maxScan = maxScan;
    }

    public static Criteria criteria(AnalyticsQuery q, Set<String> include, Set<String> exclude) {
        List<Criteria> and = new ArrayList<>();
        and.add(Criteria.where("ts").gte(Date.from(q.start())).lt(Date.from(q.endExclusive())));
        if (!q.includeBots()) {
            and.add(Criteria.where("bot").is(false));
        }
        if (!q.includeInternal()) {
            and.add(Criteria.where("internal").is(false));
            and.add(Criteria.where("test").is(false));
        }
        if (q.companyIds() != null) {
            and.add(Criteria.where("companyId").in(q.companyIds()));
        }
        if (include != null) {
            and.add(include.size() == 1 ? Criteria.where("name").is(include.iterator().next()) : Criteria.where("name").in(include));
        }
        if (exclude != null && !exclude.isEmpty()) {
            and.add(Criteria.where("name").nin(exclude));
        }
        for (Map.Entry<String, String> f : q.filters().entrySet()) {
            String field = EventFilter.FIELDS.get(f.getKey());
            if (field == null) {
                continue;
            }
            if (EventFilter.isPrefix(f.getValue())) {
                String prefix = f.getValue().substring(0, f.getValue().length() - 1);
                and.add(Criteria.where(field).regex("^" + java.util.regex.Pattern.quote(prefix)));
            } else {
                and.add(Criteria.where(field).is(EventFilter.typed(f.getKey(), f.getValue())));
            }
        }
        return new Criteria().andOperator(and.toArray(new Criteria[0]));
    }

    @Override
    public List<AnalyticsEvent> events(AnalyticsQuery query, Set<String> include, Set<String> exclude) {
        Query q = Query.query(criteria(query, include, exclude)).with(Sort.by("ts")).limit((int) Math.min(Integer.MAX_VALUE, maxScan));
        q.fields().exclude("rcv").exclude("botReason");
        return mongo.find(q, AnalyticsEvent.class, collection);
    }

    @Override
    public List<Group> group(AnalyticsQuery query, String name, List<String> keys) {
        Document id = new Document();
        for (String k : keys) {
            id.append(k.replace('.', '_'), "$" + (k.equals("slot") ? "props.slot" : k));
        }
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(criteria(query, Set.of(name), null)),
                ctx -> new Document("$group", new Document("_id", id)
                        .append("n", new Document("$sum", 1))
                        .append("ps", new Document("$sum", new Document("$ifNull", List.of("$position", 0))))
                        .append("pc", new Document("$sum", new Document("$cond", List.of(new Document("$ne", List.of(new Document("$ifNull", List.of("$position", null)), null)), 1, 0))))
                        .append("v", new Document("$addToSet", "$vid"))),
                ctx -> new Document("$project", new Document("n", 1).append("ps", 1).append("pc", 1)
                        .append("u", new Document("$size", "$v"))))
                .withOptions(AggregationOptions.builder().allowDiskUse(true).build());
        AggregationResults<Document> res = mongo.aggregate(agg, collection, Document.class);
        List<Group> out = new ArrayList<>();
        for (Document d : res.getMappedResults()) {
            Document k = d.get("_id", Document.class);
            Map<String, String> key = new LinkedHashMap<>();
            for (String f : keys) {
                Object v = k == null ? null : k.get(f.replace('.', '_'));
                key.put(f, v == null ? null : String.valueOf(v));
            }
            out.add(new Group(key, num(d.get("n")), num(d.get("u")), num(d.get("ps")), num(d.get("pc"))));
        }
        return out;
    }

    @Override
    public List<AnalyticsEvent> recent(long sinceMs, Set<String> companyIds, int limit) {
        Criteria c = Criteria.where("ts").gte(new Date(sinceMs)).and("bot").is(false).and("internal").is(false).and("test").is(false);
        if (companyIds != null) {
            c = c.and("companyId").in(companyIds);
        }
        Query q = Query.query(c).with(Sort.by(Sort.Direction.DESC, "ts")).limit(limit);
        q.fields().include("ts", "name", "path", "vid", "entityType", "entityId", "companyId", "sid", "country");
        return mongo.find(q, AnalyticsEvent.class, collection);
    }

    @Override
    public List<DimensionCount> dimension(AnalyticsQuery query, String field, String prefix, int limit) {
        Criteria c = criteria(query, null, java.util.Set.of("item_impression"));
        List<Criteria> extra = new ArrayList<>();
        extra.add(c);
        extra.add(Criteria.where(field).ne(null));
        if (prefix != null && !prefix.isBlank()) {
            extra.add(Criteria.where(field).regex("^" + java.util.regex.Pattern.quote(prefix), "i"));
        }
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(new Criteria().andOperator(extra.toArray(new Criteria[0]))),
                ctx -> new Document("$group", new Document("_id", "$" + field).append("n", new Document("$sum", 1))),
                ctx -> new Document("$sort", new Document("n", -1).append("_id", 1)),
                ctx -> new Document("$limit", limit))
                .withOptions(AggregationOptions.builder().allowDiskUse(true).build());
        List<DimensionCount> out = new ArrayList<>();
        for (Document d : mongo.aggregate(agg, collection, Document.class).getMappedResults()) {
            if (d.get("_id") != null) {
                out.add(new DimensionCount(String.valueOf(d.get("_id")), num(d.get("n"))));
            }
        }
        return out;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
