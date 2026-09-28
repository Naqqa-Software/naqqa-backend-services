package com.naqqa.elasticsearch.node.search;

import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.rest.support.RestApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SearchSpec {

    public enum SortType { SCORE, DOC, SHARD_DOC, FIELD }

    public record SortSpec(String field, SortType type, boolean desc, Object missing, String mode, String unmappedType, String format) {
        boolean missingFirst() {
            return "_first".equals(missing);
        }

        Object missingValue() {
            return missing == null || "_last".equals(missing) || "_first".equals(missing) ? null : missing;
        }
    }

    public record FieldRequest(String field, String format) {
    }

    private static final Set<String> KNOWN_KEYS = Set.of("query", "from", "size", "sort", "_source", "aggs", "aggregations",
        "post_filter", "highlight", "suggest", "collapse", "rescore", "search_after", "track_total_hits", "track_scores",
        "min_score", "docvalue_fields", "stored_fields", "script_fields", "fields", "version", "seq_no_primary_term",
        "explain", "profile", "indices_boost", "timeout", "terminate_after", "knn", "retriever", "pit", "runtime_mappings",
        "stats", "ext", "slice", "rank", "_name", "allow_partial_search_results", "batched_reduce_size");

    Map<String, Object> queryClause;
    boolean hasQuery;
    Map<String, Object> postFilter;
    Map<String, Object> aggs;
    int from;
    int size;
    List<SortSpec> sorts = new ArrayList<>();
    boolean explicitSort;
    boolean trackScores;
    List<Object> searchAfter;
    long trackTotalHitsUpTo = 10_000L;
    Float minScore;
    boolean fetchSource = true;
    boolean sourceExplicit;
    List<String> includes = new ArrayList<>();
    List<String> excludes = new ArrayList<>();
    List<FieldRequest> docvalueFields = new ArrayList<>();
    List<String> storedFields;
    Map<String, Object> scriptFields;
    List<FieldRequest> fields = new ArrayList<>();
    boolean version;
    boolean seqNoPrimaryTerm;
    boolean explain;
    boolean profile;
    List<Map.Entry<String, Float>> indicesBoost = new ArrayList<>();
    long timeoutMillis = -1;
    int terminateAfter;
    Map<String, Object> highlight;
    Map<String, Object> suggest;
    Map<String, Object> collapse;
    List<Map<String, Object>> rescore = new ArrayList<>();
    List<Map<String, Object>> knn = new ArrayList<>();
    Map<String, Object> retriever;
    boolean totalHitsAsInt;

    public Map<String, Object> queryClause() {
        return queryClause;
    }

    public boolean requiresRichExecution() {
        return postFilter != null || explicitSort || trackScores || searchAfter != null
            || minScore != null || sourceExplicit || !docvalueFields.isEmpty() || storedFields != null || scriptFields != null
            || !fields.isEmpty() || version || seqNoPrimaryTerm || explain || profile || !indicesBoost.isEmpty()
            || timeoutMillis >= 0 || terminateAfter > 0 || highlight != null || suggest != null || collapse != null
            || !rescore.isEmpty() || !knn.isEmpty() || retriever != null;
    }

    @SuppressWarnings("unchecked")
    public static SearchSpec parse(Map<String, Object> body, Map<String, String> params) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Map<String, String> p = params == null ? Map.of() : params;
        SearchSpec s = new SearchSpec();
        s.queryClause = SettingsMaps.asMap(b.get("query"));
        s.hasQuery = s.queryClause != null && !s.queryClause.isEmpty();
        if (s.queryClause == null && p.get("q") != null) {
            Map<String, Object> qs = new LinkedHashMap<>();
            qs.put("query", p.get("q"));
            if (p.get("df") != null) {
                qs.put("default_field", p.get("df"));
            }
            if (p.get("default_operator") != null) {
                qs.put("default_operator", p.get("default_operator"));
            }
            if (p.get("analyzer") != null) {
                qs.put("analyzer", p.get("analyzer"));
            }
            s.queryClause = Map.of("query_string", qs);
            s.hasQuery = true;
        }
        s.postFilter = SettingsMaps.asMap(b.get("post_filter"));
        s.aggs = SettingsMaps.asMap(b.get("aggs") != null ? b.get("aggs") : b.get("aggregations"));
        s.from = SearchEngine.intValue(p.get("from") != null ? p.get("from") : b.get("from"), 0);
        s.size = SearchEngine.intValue(p.get("size") != null ? p.get("size") : b.get("size"), 10);
        if (s.from < 0 || s.size < 0) {
            throw new RestApiException(400, "[from] and [size] must be non-negative");
        }
        List<Object> sortSpecs = new ArrayList<>();
        Object sortObj = b.get("sort");
        if (sortObj instanceof List<?> l) {
            sortSpecs.addAll(l);
        } else if (sortObj != null) {
            sortSpecs.add(sortObj);
        }
        if (p.get("sort") != null) {
            for (String part : p.get("sort").split(",")) {
                if (!part.isBlank()) {
                    sortSpecs.add(part.trim());
                }
            }
        }
        s.sorts = parseSorts(sortSpecs);
        s.explicitSort = !s.sorts.isEmpty();
        s.trackScores = bool(b.get("track_scores")) || "true".equals(p.get("track_scores"));
        if (b.get("search_after") != null) {
            if (!(b.get("search_after") instanceof List<?> after)) {
                throw new RestApiException(400, "[search_after] must be an array");
            }
            s.searchAfter = new ArrayList<>(after);
        }
        s.trackTotalHitsUpTo = parseTrackTotalHitsUpTo(b.get("track_total_hits"), p.get("track_total_hits"));
        s.totalHitsAsInt = "true".equals(p.get("rest_total_hits_as_int"));
        Object minScore = b.get("min_score") != null ? b.get("min_score") : p.get("min_score");
        if (minScore != null) {
            s.minScore = minScore instanceof Number n ? n.floatValue() : Float.parseFloat(String.valueOf(minScore));
        }
        parseSource(s, b.get("_source"));
        if (p.get("_source") != null) {
            String v = p.get("_source");
            s.sourceExplicit = true;
            if ("false".equals(v)) {
                s.fetchSource = false;
            } else if (!"true".equals(v)) {
                s.includes.addAll(SettingsMaps.asStringList(v));
            }
        }
        if (p.get("_source_includes") != null) {
            s.sourceExplicit = true;
            s.includes.addAll(SettingsMaps.asStringList(p.get("_source_includes")));
        }
        if (p.get("_source_excludes") != null) {
            s.sourceExplicit = true;
            s.excludes.addAll(SettingsMaps.asStringList(p.get("_source_excludes")));
        }
        s.docvalueFields = parseFieldRequests(b.get("docvalue_fields"));
        if (p.get("docvalue_fields") != null) {
            for (String f : SettingsMaps.asStringList(p.get("docvalue_fields"))) {
                s.docvalueFields.add(new FieldRequest(f, null));
            }
        }
        Object stored = b.get("stored_fields") != null ? b.get("stored_fields") : p.get("stored_fields");
        if (stored != null) {
            s.storedFields = stored instanceof List<?> l ? new ArrayList<>(SettingsMaps.asStringList(l))
                : new ArrayList<>(SettingsMaps.asStringList(String.valueOf(stored)));
        }
        s.scriptFields = SettingsMaps.asMap(b.get("script_fields"));
        s.fields = parseFieldRequests(b.get("fields"));
        s.version = bool(b.get("version")) || "true".equals(p.get("version"));
        s.seqNoPrimaryTerm = bool(b.get("seq_no_primary_term")) || "true".equals(p.get("seq_no_primary_term"));
        s.explain = bool(b.get("explain")) || "true".equals(p.get("explain"));
        s.profile = bool(b.get("profile"));
        Object boost = b.get("indices_boost");
        if (boost instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> m = SettingsMaps.asMap(o);
                if (m != null) {
                    for (Map.Entry<String, Object> e : m.entrySet()) {
                        s.indicesBoost.add(Map.entry(e.getKey(), toFloat(e.getValue(), "indices_boost")));
                    }
                }
            }
        } else if (boost instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                s.indicesBoost.add(Map.entry(String.valueOf(e.getKey()), toFloat(e.getValue(), "indices_boost")));
            }
        }
        Object timeout = p.get("timeout") != null ? p.get("timeout") : b.get("timeout");
        if (timeout != null) {
            s.timeoutMillis = TimeValue.parseTimeValue(String.valueOf(timeout), "timeout").millis();
        }
        Object terminateAfter = p.get("terminate_after") != null ? p.get("terminate_after") : b.get("terminate_after");
        if (terminateAfter != null) {
            s.terminateAfter = SearchEngine.intValue(terminateAfter, 0);
            if (s.terminateAfter < 0) {
                throw new RestApiException(400, "terminateAfter must be > 0");
            }
        }
        s.highlight = SettingsMaps.asMap(b.get("highlight"));
        s.suggest = SettingsMaps.asMap(b.get("suggest"));
        s.collapse = SettingsMaps.asMap(b.get("collapse"));
        Object rescore = b.get("rescore");
        if (rescore instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> m = SettingsMaps.asMap(o);
                if (m != null) {
                    s.rescore.add(m);
                }
            }
        } else if (rescore instanceof Map<?, ?>) {
            s.rescore.add(SettingsMaps.asMap(rescore));
        }
        Object knn = b.get("knn");
        if (knn instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> m = SettingsMaps.asMap(o);
                if (m != null) {
                    s.knn.add(m);
                }
            }
        } else if (knn instanceof Map<?, ?>) {
            s.knn.add(SettingsMaps.asMap(knn));
        }
        s.retriever = SettingsMaps.asMap(b.get("retriever"));
        s.validate(b);
        return s;
    }

    private void validate(Map<String, Object> body) {
        if (from + size > 10_000 && searchAfter == null && retriever == null) {
            throw new RestApiException(400, "Result window is too large, from + size must be less than or equal to: [10000] but was ["
                + (from + size) + "]. See the scroll api for a more efficient way to request large data sets. This limit can be set by "
                + "changing the [index.max_result_window] index level setting.");
        }
        if (searchAfter != null) {
            if (from > 0) {
                throw new RestApiException(400, "[from] parameter must be set to 0 when [search_after] is used");
            }
            int sortCount = sorts.isEmpty() ? 1 : sorts.size();
            if (searchAfter.size() != sortCount) {
                throw new RestApiException(400, "search_after has " + searchAfter.size() + " value(s) but sort has " + sortCount + ".");
            }
        }
        if (!rescore.isEmpty()) {
            for (SortSpec sort : sorts) {
                if (sort.type() != SortType.SCORE) {
                    throw new RestApiException(400, "Cannot use [sort] option in conjunction with [rescore].");
                }
            }
            if (collapse != null) {
                throw new RestApiException(400, "cannot use `collapse` in conjunction with `rescore`");
            }
        }
        if (collapse != null) {
            if (collapse.get("field") == null) {
                throw new RestApiException(400, "[collapse] requires a [field]");
            }
            if (searchAfter != null) {
                throw new RestApiException(400, "cannot use `collapse` in conjunction with `search_after`");
            }
        }
        if (retriever != null && (hasQuery || !knn.isEmpty())) {
            throw new RestApiException(400, "cannot specify [retriever] and [" + (hasQuery ? "query" : "knn") + "]");
        }
        for (String key : body.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                throw new RestApiException(400, "Unknown key for a START_OBJECT in [" + key + "].");
            }
        }
    }

    private static void parseSource(SearchSpec s, Object src) {
        if (src == null) {
            return;
        }
        s.sourceExplicit = true;
        if (src instanceof Boolean bool) {
            s.fetchSource = bool;
        } else if (src instanceof String str) {
            s.includes.add(str);
        } else if (src instanceof List<?> l) {
            for (Object o : l) {
                s.includes.add(String.valueOf(o));
            }
        } else if (src instanceof Map<?, ?> m) {
            s.includes.addAll(SettingsMaps.asStringList(m.get("includes") != null ? m.get("includes") : m.get("include")));
            s.excludes.addAll(SettingsMaps.asStringList(m.get("excludes") != null ? m.get("excludes") : m.get("exclude")));
        }
    }

    static List<FieldRequest> parseFieldRequests(Object value) {
        List<FieldRequest> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        List<?> list = value instanceof List<?> l ? l : List.of(value);
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Object field = m.get("field");
                if (field == null) {
                    throw new RestApiException(400, "field request requires [field]");
                }
                out.add(new FieldRequest(String.valueOf(field), m.get("format") == null ? null : String.valueOf(m.get("format"))));
            } else if (o != null) {
                out.add(new FieldRequest(String.valueOf(o), null));
            }
        }
        return out;
    }

    static List<SortSpec> parseSorts(List<Object> specs) {
        List<SortSpec> out = new ArrayList<>();
        for (Object spec : specs) {
            if (spec instanceof String str) {
                String field = str;
                String order = null;
                int colon = str.lastIndexOf(':');
                if (colon > 0) {
                    field = str.substring(0, colon);
                    order = str.substring(colon + 1);
                }
                out.add(sortSpec(field, order, null, null, null, null));
            } else if (spec instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String field = String.valueOf(e.getKey());
                    if (field.equals("_script") || field.equals("_geo_distance")) {
                        throw new RestApiException(400, "sort type [" + field + "] is not supported");
                    }
                    if (e.getValue() instanceof Map<?, ?> opts) {
                        out.add(sortSpec(field, opts.get("order") == null ? null : String.valueOf(opts.get("order")),
                            opts.get("missing"), opts.get("mode") == null ? null : String.valueOf(opts.get("mode")),
                            opts.get("unmapped_type") == null ? null : String.valueOf(opts.get("unmapped_type")),
                            opts.get("format") == null ? null : String.valueOf(opts.get("format"))));
                    } else {
                        out.add(sortSpec(field, e.getValue() == null ? null : String.valueOf(e.getValue()), null, null, null, null));
                    }
                }
            } else if (spec != null) {
                throw new RestApiException(400, "malformed sort [" + spec + "]");
            }
        }
        return out;
    }

    private static SortSpec sortSpec(String field, String order, Object missing, String mode, String unmappedType, String format) {
        if (order != null && !order.equalsIgnoreCase("asc") && !order.equalsIgnoreCase("desc")) {
            throw new RestApiException(400, "Unknown SortOrder [" + order + "]");
        }
        boolean asc = order != null && order.equalsIgnoreCase("asc");
        boolean desc = order != null && order.equalsIgnoreCase("desc");
        return switch (field) {
            case "_score" -> new SortSpec(null, SortType.SCORE, !asc, null, null, null, null);
            case "_doc" -> new SortSpec(null, SortType.DOC, desc, null, null, null, null);
            case "_shard_doc" -> new SortSpec(null, SortType.SHARD_DOC, desc, null, null, null, null);
            default -> new SortSpec(field, SortType.FIELD, desc, missing, mode, unmappedType, format);
        };
    }

    public static long parseTrackTotalHitsUpTo(Object bodyValue, Object paramValue) {
        Object tth = paramValue != null ? paramValue : bodyValue;
        if (tth == null) {
            return 10_000L;
        }
        String v = String.valueOf(tth);
        if ("true".equals(v)) {
            return Long.MAX_VALUE;
        }
        if ("false".equals(v)) {
            return -1L;
        }
        long parsed;
        try {
            parsed = (long) Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new RestApiException(400, "[track_total_hits] must be a boolean or an integer but was [" + v + "]");
        }
        if (parsed < 0) {
            throw new RestApiException(400, "[track_total_hits] parameter must be positive or equals to -1, got " + v);
        }
        return parsed;
    }

    private static boolean bool(Object v) {
        return Boolean.TRUE.equals(v) || "true".equals(v);
    }

    private static float toFloat(Object v, String name) {
        if (v instanceof Number n) {
            return n.floatValue();
        }
        try {
            return Float.parseFloat(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new RestApiException(400, "[" + name + "] value must be a number but was [" + v + "]");
        }
    }
}
