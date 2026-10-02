package com.naqqa.elasticsearch.node.search;

import com.naqqa.elasticsearch.action.search.IndexNameQuery;
import com.naqqa.elasticsearch.common.time.DateFormatter;
import com.naqqa.elasticsearch.index.engine.segment.EngineNestedDocMapping;
import com.naqqa.elasticsearch.index.engine.segment.EngineVectorAccessorProvider;
import com.naqqa.elasticsearch.search.vectors.query.VectorSegmentAccessorProvider;
import com.naqqa.elasticsearch.common.time.DateMathParser;
import com.naqqa.elasticsearch.index.query.QueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParser;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.script.ScriptService;
import com.naqqa.elasticsearch.search.advanced.common.QueryBuilderToQuery;
import com.naqqa.elasticsearch.search.query.BooleanQuery;
import com.naqqa.elasticsearch.search.query.BoostQuery;
import com.naqqa.elasticsearch.search.query.ConstantScoreQuery;
import com.naqqa.elasticsearch.search.query.DisjunctionMaxQuery;
import com.naqqa.elasticsearch.search.query.MatchAllDocsQuery;
import com.naqqa.elasticsearch.search.query.MatchNoDocsQuery;
import com.naqqa.elasticsearch.search.query.PhraseQuery;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.TermQuery;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class QueryFactory {

    public record FieldType(String type, String format, double scalingFactor) {
    }

    private static final Set<String> INTEGRAL = Set.of("long", "integer", "short", "byte", "unsigned_long");
    private static final String DEFAULT_DATE_FORMAT = "strict_date_optional_time||epoch_millis";

    private final ScriptService scriptService;
    private volatile VectorSegmentAccessorProvider vectorProvider;
    private volatile QueryBuilderToQuery.ConversionContext context;

    public QueryFactory(ScriptService scriptService) {
        this.scriptService = scriptService;
        this.vectorProvider = new EngineVectorAccessorProvider();
        this.context = buildContext();
    }

    private QueryBuilderToQuery.ConversionContext buildContext() {
        return QueryBuilderToQuery.ConversionContext.of(vectorProvider, new EngineNestedDocMapping(List.of()), scriptService);
    }

    public ScriptService scriptService() {
        return scriptService;
    }

    public VectorSegmentAccessorProvider vectorProvider() {
        return vectorProvider;
    }

    public void setVectorProvider(VectorSegmentAccessorProvider provider) {
        this.vectorProvider = provider;
        this.context = buildContext();
    }

    public Query toQuery(Map<String, Object> clause) {
        return toQuery(clause, null);
    }

    public Query toQuery(Map<String, Object> clause, Function<String, FieldType> fieldTypes,
                         Function<String, com.naqqa.elasticsearch.analysis.Analyzer> searchAnalyzers) {
        return toQuery(QueryTextAnalyzer.rewrite(clause, searchAnalyzers), fieldTypes);
    }

    public Query toQuery(Map<String, Object> clause, Function<String, FieldType> fieldTypes) {
        if (clause == null || clause.isEmpty()) {
            return new MatchAllDocsQuery();
        }
        validateKnn(clause, fieldTypes, 0);
        if (needsMappingAwareness(clause, fieldTypes)) {
            return convertAware(clause, fieldTypes);
        }
        return delegate(clause);
    }

    private void validateKnn(Object node, Function<String, FieldType> fieldTypes, int depth) {
        if (depth > 32) {
            return;
        }
        if (node instanceof List<?> list) {
            for (Object o : list) {
                validateKnn(o, fieldTypes, depth + 1);
            }
            return;
        }
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if ("knn".equals(e.getKey()) && e.getValue() instanceof Map<?, ?> body && body.get("field") != null) {
                String field = String.valueOf(body.get("field"));
                if (vectorProvider == null) {
                    throw new RestApiException(400, "knn not supported for this field [" + field + "]");
                }
                if (fieldTypes != null) {
                    FieldType type = fieldTypes.apply(field);
                    if (type == null) {
                        throw new RestApiException(400, "failed to create query: field [" + field + "] does not exist in the mapping");
                    }
                    if (!"dense_vector".equals(type.type())) {
                        throw new RestApiException(400, "[knn] queries are only supported on [dense_vector] fields");
                    }
                }
            }
            if (e.getValue() instanceof Map<?, ?> || e.getValue() instanceof List<?>) {
                validateKnn(e.getValue(), fieldTypes, depth + 1);
            }
        }
    }

    private static boolean isIndexField(String field) {
        return IndexFieldMapperName.equals(field);
    }

    private static final String IndexFieldMapperName = "_index";

    private static Query indexNameQuery(Object value) {
        List<String> names = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                names.add(String.valueOf(o));
            }
        } else if (value != null) {
            names.add(String.valueOf(value));
        }
        return names.isEmpty() ? new MatchNoDocsQuery() : new ConstantScoreQuery(new IndexNameQuery(names));
    }

    private Query delegate(Map<String, Object> clause) {
        QueryBuilder builder;
        try {
            builder = QueryParser.parseQuery(clause);
        } catch (RestApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RestApiException(400, "failed to parse query: " + e.getMessage(), e);
        }
        try {
            return QueryBuilderToQuery.convert(builder, context);
        } catch (RestApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RestApiException(400, "failed to create query: " + e.getMessage(), e);
        }
    }

    private static boolean isDocValueType(FieldType type) {
        if (type == null) {
            return false;
        }
        String t = type.type();
        return INTEGRAL.contains(t) || t.equals("double") || t.equals("float") || t.equals("half_float")
            || t.equals("scaled_float") || t.equals("date") || t.equals("date_nanos") || t.equals("boolean");
    }

    private static List<Map<String, Object>> clauses(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> m = SettingsMaps.asMap(o);
                if (m != null) {
                    out.add(m);
                }
            }
        } else {
            Map<String, Object> m = SettingsMaps.asMap(value);
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }

    private static String singleKey(Map<String, Object> clause) {
        return clause.size() == 1 ? clause.keySet().iterator().next() : null;
    }

    private static String fieldOf(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        for (String key : body.keySet()) {
            if (!key.equals("boost") && !key.equals("_name")) {
                return key;
            }
        }
        return null;
    }

    private boolean needsMappingAwareness(Map<String, Object> clause, Function<String, FieldType> fieldTypes) {
        String key = singleKey(clause);
        if (key == null) {
            return false;
        }
        Map<String, Object> body = SettingsMaps.asMap(clause.get(key));
        switch (key) {
            case "bool" -> {
                if (body == null) {
                    return false;
                }
                for (String occur : List.of("must", "filter", "should", "must_not")) {
                    for (Map<String, Object> sub : clauses(body.get(occur))) {
                        if (needsMappingAwareness(sub, fieldTypes)) {
                            return true;
                        }
                    }
                }
                return false;
            }
            case "constant_score" -> {
                return body != null && body.get("filter") != null
                    && needsMappingAwareness(SettingsMaps.asMap(body.get("filter")), fieldTypes);
            }
            case "dis_max" -> {
                if (body == null) {
                    return false;
                }
                for (Map<String, Object> sub : clauses(body.get("queries"))) {
                    if (needsMappingAwareness(sub, fieldTypes)) {
                        return true;
                    }
                }
                return false;
            }
            case "range", "term", "terms", "match" -> {
                String field = fieldOf(body);
                if (field != null && isIndexField(field) && !key.equals("range")) {
                    return true;
                }
                return field != null && fieldTypes != null && isDocValueType(fieldTypes.apply(field));
            }
            case "prefix", "wildcard" -> {
                String field = fieldOf(body);
                return field != null && isIndexField(field);
            }
            default -> {
                return false;
            }
        }
    }

    private static float boostOf(Map<String, Object> body) {
        Object b = body == null ? null : body.get("boost");
        return b instanceof Number n ? n.floatValue() : b == null ? 1.0f : Float.parseFloat(String.valueOf(b));
    }

    private static Query boosted(Query q, float boost) {
        return boost == 1.0f ? q : new BoostQuery(q, boost);
    }

    private Query convertAware(Map<String, Object> clause, Function<String, FieldType> fieldTypes) {
        if (!needsMappingAwareness(clause, fieldTypes)) {
            return delegate(clause);
        }
        String key = singleKey(clause);
        Map<String, Object> body = SettingsMaps.asMap(clause.get(key));
        switch (key) {
            case "bool" -> {
                BooleanQuery.Builder b = BooleanQuery.builder();
                int shoulds = 0;
                boolean hasRequired = false;
                for (Map<String, Object> sub : clauses(body.get("must"))) {
                    b.add(convertAware(sub, fieldTypes), BooleanQuery.Occur.MUST);
                    hasRequired = true;
                }
                for (Map<String, Object> sub : clauses(body.get("filter"))) {
                    b.add(convertAware(sub, fieldTypes), BooleanQuery.Occur.FILTER);
                    hasRequired = true;
                }
                for (Map<String, Object> sub : clauses(body.get("should"))) {
                    b.add(convertAware(sub, fieldTypes), BooleanQuery.Occur.SHOULD);
                    shoulds++;
                }
                boolean anyNot = false;
                for (Map<String, Object> sub : clauses(body.get("must_not"))) {
                    b.add(convertAware(sub, fieldTypes), BooleanQuery.Occur.MUST_NOT);
                    anyNot = true;
                }
                if (!hasRequired && shoulds == 0 && anyNot) {
                    b.add(new MatchAllDocsQuery(), BooleanQuery.Occur.FILTER);
                }
                Object msm = body.get("minimum_should_match");
                if (msm != null && shoulds > 0) {
                    b.setMinimumShouldMatch(resolveMinimumShouldMatch(String.valueOf(msm), shoulds));
                } else if (shoulds > 0 && !hasRequired) {
                    b.setMinimumShouldMatch(1);
                }
                return boosted(b.build(), boostOf(body));
            }
            case "constant_score" -> {
                Query inner = convertAware(SettingsMaps.asMap(body.get("filter")), fieldTypes);
                return boosted(new ConstantScoreQuery(inner), boostOf(body));
            }
            case "dis_max" -> {
                List<Query> subs = new ArrayList<>();
                for (Map<String, Object> sub : clauses(body.get("queries"))) {
                    subs.add(convertAware(sub, fieldTypes));
                }
                float tie = body.get("tie_breaker") instanceof Number n ? n.floatValue() : 0.0f;
                return boosted(new DisjunctionMaxQuery(subs, tie), boostOf(body));
            }
            case "range" -> {
                String field = fieldOf(body);
                Map<String, Object> spec = SettingsMaps.asMap(body.get(field));
                if (spec == null) {
                    throw new RestApiException(400, "[range] query malformed, no start_object after query name");
                }
                return boosted(rangeQuery(field, fieldTypes.apply(field), spec), boostOf(spec));
            }
            case "term" -> {
                String field = fieldOf(body);
                Object raw = body.get(field);
                Map<String, Object> spec = SettingsMaps.asMap(raw);
                Object value = spec != null ? spec.get("value") : raw;
                if (isIndexField(field)) {
                    return boosted(indexNameQuery(value), spec == null ? 1.0f : boostOf(spec));
                }
                return boosted(exactQuery(field, fieldTypes.apply(field), value), spec == null ? 1.0f : boostOf(spec));
            }
            case "match" -> {
                String field = fieldOf(body);
                Object raw = body.get(field);
                Map<String, Object> spec = SettingsMaps.asMap(raw);
                Object value = spec != null ? spec.get("query") : raw;
                if (isIndexField(field)) {
                    return boosted(indexNameQuery(value), spec == null ? 1.0f : boostOf(spec));
                }
                return boosted(exactQuery(field, fieldTypes.apply(field), value), spec == null ? 1.0f : boostOf(spec));
            }
            case "prefix", "wildcard" -> {
                String field = fieldOf(body);
                Object raw = body.get(field);
                Map<String, Object> spec = SettingsMaps.asMap(raw);
                Object value = spec != null ? (spec.get("value") != null ? spec.get("value") : spec.get(key)) : raw;
                String pattern = String.valueOf(value);
                if (key.equals("prefix")) {
                    pattern = pattern + "*";
                } else {
                    pattern = pattern.replace('?', '*');
                }
                return boosted(indexNameQuery(pattern), spec == null ? 1.0f : boostOf(spec));
            }
            case "terms" -> {
                String field = fieldOf(body);
                Object raw = body.get(field);
                if (isIndexField(field)) {
                    return boosted(indexNameQuery(raw), boostOf(body));
                }
                if (!(raw instanceof List<?> values)) {
                    return delegate(clause);
                }
                BooleanQuery.Builder b = BooleanQuery.builder();
                for (Object v : values) {
                    b.add(exactQuery(field, fieldTypes.apply(field), v), BooleanQuery.Occur.SHOULD);
                }
                b.setMinimumShouldMatch(values.isEmpty() ? 0 : 1);
                return values.isEmpty() ? new MatchNoDocsQuery() : boosted(new ConstantScoreQuery(b.build()), boostOf(body));
            }
            default -> {
                return delegate(clause);
            }
        }
    }

    private static int resolveMinimumShouldMatch(String spec, int optionalClauses) {
        String s = spec.trim();
        try {
            if (s.endsWith("%")) {
                int pct = Integer.parseInt(s.substring(0, s.length() - 1));
                int n = (int) Math.floor(optionalClauses * Math.abs(pct) / 100.0);
                return pct < 0 ? optionalClauses - n : n;
            }
            int n = Integer.parseInt(s);
            return n < 0 ? Math.max(0, optionalClauses + n) : Math.min(n, optionalClauses);
        } catch (NumberFormatException e) {
            throw new RestApiException(400, "unsupported minimum_should_match [" + spec + "]");
        }
    }

    private Query exactQuery(String field, FieldType type, Object value) {
        if (value == null) {
            throw new RestApiException(400, "field [" + field + "] requires a non-null value");
        }
        String t = type.type();
        if (t.equals("boolean")) {
            boolean b = value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
            return new ConstantScoreQuery(DocValuesRangeQuery.longRange(field, b ? 1 : 0, b ? 1 : 0));
        }
        if (t.equals("date") || t.equals("date_nanos")) {
            long lo = parseDate(type, value, false, null);
            long hi = parseDate(type, value, true, null);
            return new ConstantScoreQuery(DocValuesRangeQuery.longRange(field, lo, Math.max(lo, hi)));
        }
        if (INTEGRAL.contains(t)) {
            double d = toDouble(field, value);
            if (d != Math.floor(d)) {
                return new MatchNoDocsQuery();
            }
            long v = (long) d;
            return new ConstantScoreQuery(DocValuesRangeQuery.longRange(field, v, v));
        }
        double d = toDouble(field, value);
        return new ConstantScoreQuery(numericDoubleRange(field, type, d, d));
    }

    private static double toDouble(String field, Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new RestApiException(400, "failed to create query: For input string: \"" + value + "\" on field [" + field + "]");
        }
    }

    private static DocValuesRangeQuery numericDoubleRange(String field, FieldType type, double lo, double hi) {
        DocValuesRangeQuery.Encoding encoding = switch (type.type()) {
            case "float", "half_float" -> DocValuesRangeQuery.Encoding.FLOAT;
            case "scaled_float" -> DocValuesRangeQuery.Encoding.SCALED;
            default -> DocValuesRangeQuery.Encoding.DOUBLE;
        };
        if (encoding == DocValuesRangeQuery.Encoding.FLOAT) {
            lo = (float) lo;
            hi = (float) hi;
        }
        return DocValuesRangeQuery.doubleRange(field, encoding, lo, hi, type.scalingFactor() <= 0 ? 1 : type.scalingFactor());
    }

    private static long parseDate(FieldType type, Object value, boolean roundUp, String formatOverride) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        String format = formatOverride != null ? formatOverride : type.format() != null ? type.format() : DEFAULT_DATE_FORMAT;
        try {
            DateMathParser parser = new DateMathParser(DateFormatter.forPattern(format));
            return parser.parse(String.valueOf(value), System.currentTimeMillis(), roundUp, ZoneId.of("UTC"));
        } catch (RuntimeException e) {
            throw new RestApiException(400, "failed to parse date field [" + value + "] with format [" + format + "]: " + e.getMessage(), e);
        }
    }

    private Query rangeQuery(String field, FieldType type, Map<String, Object> spec) {
        Object gte = spec.get("gte");
        Object gt = spec.get("gt");
        Object lte = spec.get("lte");
        Object lt = spec.get("lt");
        if (spec.containsKey("from") || spec.containsKey("to")) {
            boolean includeLower = !Boolean.FALSE.equals(spec.get("include_lower"));
            boolean includeUpper = !Boolean.FALSE.equals(spec.get("include_upper"));
            if (spec.get("from") != null) {
                if (includeLower) {
                    gte = spec.get("from");
                } else {
                    gt = spec.get("from");
                }
            }
            if (spec.get("to") != null) {
                if (includeUpper) {
                    lte = spec.get("to");
                } else {
                    lt = spec.get("to");
                }
            }
        }
        String t = type.type();
        String formatOverride = spec.get("format") == null ? null : String.valueOf(spec.get("format"));
        if (t.equals("date") || t.equals("date_nanos") || t.equals("boolean") || INTEGRAL.contains(t)) {
            long lo = Long.MIN_VALUE;
            long hi = Long.MAX_VALUE;
            boolean date = t.startsWith("date");
            if (gte != null) {
                lo = date ? parseDate(type, gte, false, formatOverride) : (long) Math.ceil(toDouble(field, gte));
            }
            if (gt != null) {
                long v = date ? parseDate(type, gt, true, formatOverride) : (long) Math.floor(toDouble(field, gt));
                lo = Math.max(lo, v == Long.MAX_VALUE ? v : v + 1);
            }
            if (lte != null) {
                hi = date ? parseDate(type, lte, true, formatOverride) : (long) Math.floor(toDouble(field, lte));
            }
            if (lt != null) {
                long v = date ? parseDate(type, lt, false, formatOverride) : (long) Math.ceil(toDouble(field, lt));
                hi = Math.min(hi, v == Long.MIN_VALUE ? v : v - 1);
            }
            if (lo > hi) {
                return new MatchNoDocsQuery();
            }
            return new ConstantScoreQuery(DocValuesRangeQuery.longRange(field, lo, hi));
        }
        double lo = Double.NEGATIVE_INFINITY;
        double hi = Double.POSITIVE_INFINITY;
        if (gte != null) {
            lo = toDouble(field, gte);
        }
        if (gt != null) {
            lo = Math.max(lo, Math.nextUp(toDouble(field, gt)));
        }
        if (lte != null) {
            hi = toDouble(field, lte);
        }
        if (lt != null) {
            hi = Math.min(hi, Math.nextDown(toDouble(field, lt)));
        }
        if (lo > hi) {
            return new MatchNoDocsQuery();
        }
        return new ConstantScoreQuery(numericDoubleRange(field, type, lo, hi));
    }

    public static String normalizeType(String type) {
        return type == null ? null : type.toLowerCase(Locale.ROOT);
    }

    public static boolean isTransportable(Query query) {
        if (query instanceof MatchAllDocsQuery || query instanceof MatchNoDocsQuery || query instanceof TermQuery
            || query instanceof PhraseQuery) {
            return true;
        }
        if (query instanceof BooleanQuery bq) {
            for (BooleanQuery.BooleanClause clause : bq.clauses()) {
                if (!isTransportable(clause.query())) {
                    return false;
                }
            }
            return true;
        }
        if (query instanceof BoostQuery bq) {
            return isTransportable(bq.inner());
        }
        if (query instanceof ConstantScoreQuery csq) {
            return isTransportable(csq.inner());
        }
        if (query instanceof DisjunctionMaxQuery dmq) {
            for (Query q : dmq.subQueries()) {
                if (!isTransportable(q)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}
