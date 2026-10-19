package com.naqqa.elasticsearch.os;

import com.naqqa.elasticsearch.os.SearchModels.AggRequest;
import com.naqqa.elasticsearch.os.SearchModels.FacetBucket;
import com.naqqa.elasticsearch.os.SearchModels.RangeAggBucket;
import com.naqqa.elasticsearch.os.SearchModels.SearchFilter;
import com.naqqa.elasticsearch.os.SearchModels.SearchHit;
import com.naqqa.elasticsearch.os.SearchModels.SearchQuery;
import com.naqqa.elasticsearch.os.SearchModels.SearchResult;
import com.naqqa.elasticsearch.os.SearchModels.SortClause;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory execution of the {@code POST /api/search} contract, ported from {@code search-engine.ts}:
 * full-text multi_match scoring, term/terms/range/exists/prefix filters, sort, from/size + search_after
 * pagination and terms/range/date_histogram aggregations. A host that wants true Lucene-backed execution
 * can run the same {@link SearchQuery} against the engine in this module instead.
 */
public final class SearchExecutor {

    private static final int DEFAULT_SIZE = 20;

    private SearchExecutor() {
    }

    public static Object defaultField(Object doc, String name) {
        Object current = doc;
        for (String part : name.split("\\.")) {
            if (current == null) {
                return null;
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private static Double toNumber(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (value instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        if (value instanceof String s && !s.trim().isEmpty()) {
            try {
                double d = Double.parseDouble(s.trim());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean matchesFilter(SearchDataset dataset, Map<String, Object> doc, SearchFilter filter) {
        Object raw = dataset.field(doc, filter.field());
        switch (filter.type()) {
            case "term":
                if (raw instanceof List<?> list) {
                    for (Object v : list) {
                        if (String.valueOf(v).equals(String.valueOf(filter.value()))) {
                            return true;
                        }
                    }
                    return false;
                }
                return String.valueOf(raw).equals(String.valueOf(filter.value()));
            case "terms": {
                List<Object> values = filter.values();
                if (values == null || values.isEmpty()) {
                    return true;
                }
                List<String> wanted = values.stream().map(String::valueOf).toList();
                if (raw instanceof List<?> list) {
                    for (Object v : list) {
                        if (wanted.contains(String.valueOf(v))) {
                            return true;
                        }
                    }
                    return false;
                }
                return wanted.contains(String.valueOf(raw));
            }
            case "range": {
                Double n = toNumber(raw);
                if (n == null) {
                    return false;
                }
                if (filter.gte() != null && n < filter.gte()) {
                    return false;
                }
                if (filter.gt() != null && n <= filter.gt()) {
                    return false;
                }
                if (filter.lte() != null && n > filter.lte()) {
                    return false;
                }
                if (filter.lt() != null && n >= filter.lt()) {
                    return false;
                }
                return true;
            }
            case "exists":
                return raw != null && !"".equals(raw);
            case "prefix": {
                String needle = String.valueOf(filter.value()).toLowerCase();
                if (needle.isEmpty()) {
                    return true;
                }
                return String.valueOf(raw == null ? "" : raw).toLowerCase().startsWith(needle);
            }
            default:
                return true;
        }
    }

    private static Object sortValue(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : 0.0;
        }
        if (value instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        if (value == null) {
            return "";
        }
        return String.valueOf(value);
    }

    private static int compareScalar(Object a, Object b) {
        if (a instanceof Double da && b instanceof Double db) {
            return Double.compare(da, db);
        }
        String sa = String.valueOf(a);
        String sb = String.valueOf(b);
        return sa.compareTo(sb);
    }

    private static int compareTuples(List<Object> a, List<Object> b, List<String> dirs) {
        int len = Math.min(a.size(), b.size());
        for (int i = 0; i < len; i++) {
            int cmp = compareScalar(a.get(i), b.get(i));
            if (cmp != 0) {
                return "desc".equals(dirs.get(i)) ? -cmp : cmp;
            }
        }
        return 0;
    }

    private static List<SortClause> effectiveSort(SearchQuery query) {
        List<SortClause> clauses = new ArrayList<>();
        boolean hasText = query.text() != null && !query.text().trim().isEmpty();
        if (query.sort() != null && !query.sort().isEmpty()) {
            clauses.addAll(query.sort());
        } else if (hasText) {
            clauses.add(new SortClause("_score", "desc"));
        }
        clauses.add(new SortClause("_id", "asc"));
        return clauses;
    }

    private static List<Object> buildTuple(SearchDataset dataset, Map<String, Object> doc, String id, double score,
                                           List<SortClause> clauses) {
        List<Object> tuple = new ArrayList<>();
        for (SortClause clause : clauses) {
            if ("_score".equals(clause.field())) {
                tuple.add(score);
            } else if ("_id".equals(clause.field())) {
                tuple.add(id);
            } else {
                tuple.add(sortValue(dataset.field(doc, clause.field())));
            }
        }
        return tuple;
    }

    private static String bucketKeyForRange(RangeAggBucket bucket) {
        if (bucket.key() != null) {
            return bucket.key();
        }
        String from = bucket.from() == null ? "*" : String.valueOf(bucket.from());
        String to = bucket.to() == null ? "*" : String.valueOf(bucket.to());
        return from + "-" + to;
    }

    private static List<FacetBucket> computeAgg(SearchDataset dataset, List<Map<String, Object>> docs, AggRequest agg) {
        if ("terms".equals(agg.type())) {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (Map<String, Object> doc : docs) {
                Object raw = dataset.field(doc, agg.field());
                List<?> values = raw instanceof List<?> list ? list : java.util.Collections.singletonList(raw);
                for (Object v : values) {
                    if (v == null || "".equals(v)) {
                        continue;
                    }
                    String key = String.valueOf(v);
                    counts.merge(key, 1L, Long::sum);
                }
            }
            List<FacetBucket> buckets = new ArrayList<>();
            counts.forEach((key, count) -> buckets.add(new FacetBucket(key, count, null, null)));
            buckets.sort((a, b) -> b.count() != a.count() ? Long.compare(b.count(), a.count()) : a.key().compareTo(b.key()));
            return agg.size() != null ? buckets.subList(0, Math.min(agg.size(), buckets.size())) : buckets;
        }
        if ("range".equals(agg.type())) {
            List<FacetBucket> out = new ArrayList<>();
            for (RangeAggBucket range : agg.ranges()) {
                long count = 0;
                for (Map<String, Object> doc : docs) {
                    Double n = toNumber(dataset.field(doc, agg.field()));
                    if (n == null) {
                        continue;
                    }
                    if (range.from() != null && n < range.from()) {
                        continue;
                    }
                    if (range.to() != null && n >= range.to()) {
                        continue;
                    }
                    count++;
                }
                out.add(new FacetBucket(bucketKeyForRange(range), count, range.from(), range.to()));
            }
            return out;
        }
        long scale = "ns".equals(agg.fieldUnit()) ? 1_000_000L : 1L;
        long intervalMs = agg.intervalMs() == null ? 1 : agg.intervalMs();
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> doc : docs) {
            Double n = toNumber(dataset.field(doc, agg.field()));
            if (n == null) {
                continue;
            }
            long ms = (long) (n / scale);
            long bucket = Math.floorDiv(ms, intervalMs) * intervalMs;
            counts.merge(bucket, 1L, Long::sum);
        }
        List<FacetBucket> buckets = new ArrayList<>();
        counts.forEach((bucket, count) -> buckets.add(new FacetBucket(String.valueOf(bucket), count, (double) bucket, (double) (bucket + intervalMs))));
        buckets.sort((a, b) -> Double.compare(a.from(), b.from()));
        return buckets;
    }

    private record Scored(Map<String, Object> doc, String id, double score, List<Object> tuple) {
    }

    public static SearchResult runSearch(SearchDataset dataset, SearchQuery query) {
        long started = System.currentTimeMillis();
        List<String> queryTokens = query.text() != null && !query.text().trim().isEmpty()
                ? SearchText.tokenize(query.text()) : List.of();
        List<SearchFilter> filters = query.filters() == null ? List.of() : query.filters();
        List<SortClause> clauses = effectiveSort(query);
        List<String> dirs = clauses.stream().map(SortClause::dir).toList();

        List<Scored> matched = new ArrayList<>();
        for (Map<String, Object> doc : dataset.docs()) {
            boolean keep = true;
            for (SearchFilter filter : filters) {
                if (!matchesFilter(dataset, doc, filter)) {
                    keep = false;
                    break;
                }
            }
            if (!keep) {
                continue;
            }
            double score = 1;
            if (!queryTokens.isEmpty()) {
                score = SearchText.scoreText(SearchText.tokenize(dataset.text(doc)), queryTokens);
                if (score <= 0) {
                    continue;
                }
            }
            String id = dataset.id(doc);
            matched.add(new Scored(doc, id, score, buildTuple(dataset, doc, id, score, clauses)));
        }

        matched.sort((a, b) -> compareTuples(a.tuple(), b.tuple(), dirs));

        Map<String, List<FacetBucket>> aggregations = new LinkedHashMap<>();
        if (query.aggs() != null) {
            List<Map<String, Object>> matchedDocs = matched.stream().map(Scored::doc).toList();
            for (AggRequest agg : query.aggs()) {
                aggregations.put(agg.name(), computeAgg(dataset, matchedDocs, agg));
            }
        }

        List<Scored> window = matched;
        if (query.searchAfter() != null && !query.searchAfter().isEmpty()) {
            List<Object> cursor = query.searchAfter().stream().map(SearchExecutor::sortValue).toList();
            List<Scored> filtered = new ArrayList<>();
            for (Scored m : matched) {
                if (compareTuples(m.tuple(), cursor, dirs) > 0) {
                    filtered.add(m);
                }
            }
            window = filtered;
        } else if (query.from() != null && query.from() > 0) {
            window = matched.subList(Math.min(query.from(), matched.size()), matched.size());
        }
        int size = query.size() != null ? query.size() : DEFAULT_SIZE;
        List<Scored> page = window.subList(0, Math.min(size, window.size()));

        List<SearchHit> hits = new ArrayList<>();
        for (Scored m : page) {
            hits.add(new SearchHit(m.id(), m.score(), m.doc(), m.tuple()));
        }
        return new SearchResult(hits, matched.size(), aggregations, System.currentTimeMillis() - started);
    }
}
