package com.naqqa.elasticsearch.index.query.request;

import com.naqqa.elasticsearch.index.query.QueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParseUtils;
import com.naqqa.elasticsearch.index.query.QueryParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class SearchSourceBuilder {

    private QueryBuilder query;
    private int from = -1;
    private int size = -1;
    private final List<SortBuilder> sorts = new ArrayList<>();
    private FetchSourceContext fetchSource;
    private final List<Object> fields = new ArrayList<>();
    private final List<Object> docValueFields = new ArrayList<>();
    private List<String> storedFields;
    private final Map<String, ScriptField> scriptFields = new LinkedHashMap<>();
    private QueryBuilder postFilter;
    private Float minScore;
    private String timeout;
    private Integer terminateAfter;
    private boolean trackScores = false;
    private Object trackTotalHits;
    private boolean version = false;
    private boolean seqNoPrimaryTerm = false;
    private boolean explain = false;
    private boolean profile = false;
    private CollapseBuilder collapse;
    private final List<RescoreBuilder> rescores = new ArrayList<>();
    private final Map<String, Float> indicesBoost = new LinkedHashMap<>();
    private final List<String> stats = new ArrayList<>();
    private Map<String, Object> aggregations;
    private Map<String, Object> highlight;
    private Map<String, Object> suggest;

    @SuppressWarnings("unchecked")
    public static SearchSourceBuilder fromMap(Map<String, Object> value) {
        SearchSourceBuilder builder = new SearchSourceBuilder();
        Map<String, Object> map = new LinkedHashMap<>(value);
        Object query = map.remove("query");
        if (query != null) {
            builder.query = QueryParser.parseQuery((Map<String, Object>) query);
        }
        Object from = map.remove("from");
        if (from != null) {
            builder.from = QueryParseUtils.asInt(from);
        }
        Object size = map.remove("size");
        if (size != null) {
            builder.size = QueryParseUtils.asInt(size);
        }
        Object sort = map.remove("sort");
        if (sort != null) {
            for (Object o : QueryParseUtils.asList(sort, "sort")) {
                builder.sorts.add(parseSortEntry(o));
            }
        }
        Object source = map.remove("_source");
        if (source != null) {
            builder.fetchSource = FetchSourceContext.parse(source);
        }
        Object fields = map.remove("fields");
        if (fields != null) {
            builder.fields.addAll(parseFieldList(fields));
        }
        Object docvalueFields = map.remove("docvalue_fields");
        if (docvalueFields != null) {
            builder.docValueFields.addAll(parseFieldList(docvalueFields));
        }
        Object storedFields = map.remove("stored_fields");
        if (storedFields != null) {
            builder.storedFields = QueryParseUtils.asStringList(storedFields);
        }
        Object scriptFields = map.remove("script_fields");
        if (scriptFields != null) {
            for (Map.Entry<String, Object> e : QueryParseUtils.asMap(scriptFields, "script_fields").entrySet()) {
                builder.scriptFields.put(e.getKey(), ScriptField.fromMap(e.getKey(), QueryParseUtils.asMap(e.getValue(), e.getKey())));
            }
        }
        Object postFilter = map.remove("post_filter");
        if (postFilter != null) {
            builder.postFilter = QueryParser.parseQuery((Map<String, Object>) postFilter);
        }
        Object minScore = map.remove("min_score");
        if (minScore != null) {
            builder.minScore = QueryParseUtils.asFloat(minScore);
        }
        Object timeout = map.remove("timeout");
        if (timeout != null) {
            builder.timeout = QueryParseUtils.asString(timeout);
        }
        Object terminateAfter = map.remove("terminate_after");
        if (terminateAfter != null) {
            builder.terminateAfter = QueryParseUtils.asInt(terminateAfter);
        }
        Object trackScores = map.remove("track_scores");
        if (trackScores != null) {
            builder.trackScores = QueryParseUtils.asBoolean(trackScores);
        }
        Object trackTotalHits = map.remove("track_total_hits");
        if (trackTotalHits != null) {
            builder.trackTotalHits = trackTotalHits instanceof Boolean ? trackTotalHits : QueryParseUtils.asInt(trackTotalHits);
        }
        Object version = map.remove("version");
        if (version != null) {
            builder.version = QueryParseUtils.asBoolean(version);
        }
        Object seqNo = map.remove("seq_no_primary_term");
        if (seqNo != null) {
            builder.seqNoPrimaryTerm = QueryParseUtils.asBoolean(seqNo);
        }
        Object explain = map.remove("explain");
        if (explain != null) {
            builder.explain = QueryParseUtils.asBoolean(explain);
        }
        Object profile = map.remove("profile");
        if (profile != null) {
            builder.profile = QueryParseUtils.asBoolean(profile);
        }
        Object collapse = map.remove("collapse");
        if (collapse != null) {
            builder.collapse = CollapseBuilder.fromMap(QueryParseUtils.asMap(collapse, "collapse"));
        }
        Object rescore = map.remove("rescore");
        if (rescore != null) {
            for (Object o : QueryParseUtils.asList(rescore, "rescore")) {
                builder.rescores.add(RescoreBuilder.fromMap(QueryParseUtils.asMap(o, "rescore")));
            }
        }
        Object indicesBoost = map.remove("indices_boost");
        if (indicesBoost != null) {
            for (Object o : QueryParseUtils.asList(indicesBoost, "indices_boost")) {
                Map<String, Object> entry = QueryParseUtils.asMap(o, "indices_boost");
                for (Map.Entry<String, Object> e : entry.entrySet()) {
                    builder.indicesBoost.put(e.getKey(), QueryParseUtils.asFloat(e.getValue()));
                }
            }
        }
        Object stats = map.remove("stats");
        if (stats != null) {
            builder.stats.addAll(QueryParseUtils.asStringList(stats));
        }
        Object aggs = map.remove("aggs");
        if (aggs == null) {
            aggs = map.remove("aggregations");
        }
        if (aggs != null) {
            builder.aggregations = QueryParseUtils.asMap(aggs, "aggregations");
        }
        Object highlight = map.remove("highlight");
        if (highlight != null) {
            builder.highlight = QueryParseUtils.asMap(highlight, "highlight");
        }
        Object suggest = map.remove("suggest");
        if (suggest != null) {
            builder.suggest = QueryParseUtils.asMap(suggest, "suggest");
        }
        if (!map.isEmpty()) {
            throw QueryParseUtils.error("request does not support [{}]", map.keySet().iterator().next());
        }
        return builder;
    }

    @SuppressWarnings("unchecked")
    private static SortBuilder parseSortEntry(Object entry) {
        if (entry instanceof String s) {
            if ("_score".equals(s)) {
                return new ScoreSortBuilder();
            }
            return FieldSortBuilder.fromScalar(s, null);
        }
        Map<String, Object> map = QueryParseUtils.asMap(entry, "sort");
        Map.Entry<String, Object> e = QueryParseUtils.singleField("sort", map);
        String key = e.getKey();
        Object val = e.getValue();
        if ("_score".equals(key)) {
            return val instanceof Map<?, ?> ? ScoreSortBuilder.fromMap(QueryParseUtils.asMap(val, "_score")) : new ScoreSortBuilder();
        }
        if ("_geo_distance".equals(key)) {
            Map<String, Object> geoMap = new LinkedHashMap<>(QueryParseUtils.asMap(val, "_geo_distance"));
            String fieldName = null;
            Object points = null;
            for (Map.Entry<String, Object> ge : geoMap.entrySet()) {
                if (!geoDistanceSortParamKeys().contains(ge.getKey())) {
                    fieldName = ge.getKey();
                    points = ge.getValue();
                }
            }
            if (fieldName == null) {
                throw QueryParseUtils.error("_geo_distance sort requires a field with point(s)");
            }
            geoMap.remove(fieldName);
            return GeoDistanceSortBuilder.fromMap(fieldName, points, geoMap);
        }
        if ("_script".equals(key)) {
            return ScriptSortBuilder.fromMap(QueryParseUtils.asMap(val, "_script"));
        }
        if (val instanceof String s) {
            return FieldSortBuilder.fromScalar(key, s);
        }
        return FieldSortBuilder.fromMap(key, QueryParseUtils.asMap(val, key));
    }

    private static java.util.Set<String> geoDistanceSortParamKeys() {
        return java.util.Set.of("order", "unit", "distance_type", "mode", "nested");
    }

    private static List<Object> parseFieldList(Object value) {
        List<Object> out = new ArrayList<>();
        for (Object o : QueryParseUtils.asList(value, "fields")) {
            out.add(o instanceof Map<?, ?> ? QueryParseUtils.asMap(o, "fields") : o);
        }
        return out;
    }

    public QueryBuilder query() {
        return query;
    }

    public void query(QueryBuilder query) {
        this.query = query;
    }

    public int from() {
        return from;
    }

    public int size() {
        return size;
    }

    public List<SortBuilder> sorts() {
        return sorts;
    }

    public FetchSourceContext fetchSource() {
        return fetchSource;
    }

    public Map<String, ScriptField> scriptFields() {
        return scriptFields;
    }

    public QueryBuilder postFilter() {
        return postFilter;
    }

    public CollapseBuilder collapse() {
        return collapse;
    }

    public List<RescoreBuilder> rescores() {
        return rescores;
    }

    public Map<String, Object> aggregations() {
        return aggregations;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (query != null) {
            m.put("query", query.toMap());
        }
        if (from >= 0) {
            m.put("from", from);
        }
        if (size >= 0) {
            m.put("size", size);
        }
        if (!sorts.isEmpty()) {
            m.put("sort", sorts.stream().map(SortBuilder::toMap).collect(Collectors.toList()));
        }
        if (fetchSource != null) {
            m.put("_source", fetchSource.toMapOrBoolean());
        }
        if (!fields.isEmpty()) {
            m.put("fields", fields);
        }
        if (!docValueFields.isEmpty()) {
            m.put("docvalue_fields", docValueFields);
        }
        if (storedFields != null) {
            m.put("stored_fields", storedFields);
        }
        if (!scriptFields.isEmpty()) {
            Map<String, Object> sf = new LinkedHashMap<>();
            for (Map.Entry<String, ScriptField> e : scriptFields.entrySet()) {
                sf.put(e.getKey(), e.getValue().toMap());
            }
            m.put("script_fields", sf);
        }
        if (postFilter != null) {
            m.put("post_filter", postFilter.toMap());
        }
        if (minScore != null) {
            m.put("min_score", minScore);
        }
        if (timeout != null) {
            m.put("timeout", timeout);
        }
        if (terminateAfter != null) {
            m.put("terminate_after", terminateAfter);
        }
        if (trackScores) {
            m.put("track_scores", true);
        }
        if (trackTotalHits != null) {
            m.put("track_total_hits", trackTotalHits);
        }
        if (version) {
            m.put("version", true);
        }
        if (seqNoPrimaryTerm) {
            m.put("seq_no_primary_term", true);
        }
        if (explain) {
            m.put("explain", true);
        }
        if (profile) {
            m.put("profile", true);
        }
        if (collapse != null) {
            m.put("collapse", collapse.toMap());
        }
        if (!rescores.isEmpty()) {
            m.put("rescore", rescores.size() == 1 ? rescores.get(0).toMap() : rescores.stream().map(RescoreBuilder::toMap).collect(Collectors.toList()));
        }
        if (!indicesBoost.isEmpty()) {
            List<Object> list = new ArrayList<>();
            for (Map.Entry<String, Float> e : indicesBoost.entrySet()) {
                list.add(Map.of(e.getKey(), e.getValue()));
            }
            m.put("indices_boost", list);
        }
        if (!stats.isEmpty()) {
            m.put("stats", stats);
        }
        if (aggregations != null) {
            m.put("aggregations", aggregations);
        }
        if (highlight != null) {
            m.put("highlight", highlight);
        }
        if (suggest != null) {
            m.put("suggest", suggest);
        }
        return m;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SearchSourceBuilder other)) {
            return false;
        }
        return Objects.equals(query, other.query) && from == other.from && size == other.size && sorts.equals(other.sorts)
            && Objects.equals(fetchSource, other.fetchSource) && fields.equals(other.fields) && docValueFields.equals(other.docValueFields)
            && Objects.equals(storedFields, other.storedFields) && scriptFields.equals(other.scriptFields)
            && Objects.equals(postFilter, other.postFilter) && Objects.equals(minScore, other.minScore) && Objects.equals(timeout, other.timeout)
            && Objects.equals(terminateAfter, other.terminateAfter) && trackScores == other.trackScores
            && Objects.equals(trackTotalHits, other.trackTotalHits) && version == other.version && seqNoPrimaryTerm == other.seqNoPrimaryTerm
            && explain == other.explain && profile == other.profile && Objects.equals(collapse, other.collapse) && rescores.equals(other.rescores)
            && indicesBoost.equals(other.indicesBoost) && stats.equals(other.stats) && Objects.equals(aggregations, other.aggregations)
            && Objects.equals(highlight, other.highlight) && Objects.equals(suggest, other.suggest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(query, from, size, sorts, fetchSource, fields, docValueFields, storedFields, scriptFields,
            postFilter, minScore, timeout, terminateAfter, trackScores, trackTotalHits, version, seqNoPrimaryTerm,
            explain, profile, collapse, rescores, indicesBoost, stats, aggregations, highlight, suggest);
    }
}
