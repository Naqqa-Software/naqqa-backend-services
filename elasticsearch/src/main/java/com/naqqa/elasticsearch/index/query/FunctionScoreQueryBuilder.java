package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.script.Script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class FunctionScoreQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "function_score";

    public enum ScoreMode {
        MULTIPLY, SUM, AVG, FIRST, MAX, MIN;

        static ScoreMode fromString(String s) {
            return ScoreMode.valueOf(s.toUpperCase(Locale.ROOT));
        }

        String toValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum BoostMode {
        MULTIPLY, REPLACE, SUM, AVG, MAX, MIN;

        static BoostMode fromString(String s) {
            return BoostMode.valueOf(s.toUpperCase(Locale.ROOT));
        }

        String toValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final class FilterFunctionBuilder {

        private final QueryBuilder filter;
        private final ScoreFunctionBuilder function;
        private final Float weight;

        public FilterFunctionBuilder(QueryBuilder filter, ScoreFunctionBuilder function) {
            this(filter, function, null);
        }

        public FilterFunctionBuilder(QueryBuilder filter, ScoreFunctionBuilder function, Float weight) {
            this.filter = filter;
            this.function = Objects.requireNonNull(function);
            this.weight = weight;
        }

        public QueryBuilder filter() {
            return filter;
        }

        public ScoreFunctionBuilder function() {
            return function;
        }

        public Float weight() {
            return weight;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (filter != null) {
                m.put("filter", filter.toMap());
            }
            m.putAll(function.toMap());
            if (weight != null) {
                m.put("weight", weight);
            }
            return m;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof FilterFunctionBuilder other)) {
                return false;
            }
            return Objects.equals(filter, other.filter) && function.equals(other.function) && Objects.equals(weight, other.weight);
        }

        @Override
        public int hashCode() {
            return Objects.hash(filter, function, weight);
        }
    }

    private QueryBuilder query;
    private final List<FilterFunctionBuilder> functions = new ArrayList<>();
    private ScoreMode scoreMode = ScoreMode.MULTIPLY;
    private BoostMode boostMode = BoostMode.MULTIPLY;
    private Float maxBoost;
    private Float minScore;

    public FunctionScoreQueryBuilder(QueryBuilder query) {
        this.query = query == null ? new MatchAllQueryBuilder() : query;
    }

    public FunctionScoreQueryBuilder addFunction(FilterFunctionBuilder function) {
        functions.add(function);
        return this;
    }

    private static final Set<String> FUNCTION_TYPES = Set.of("weight", "field_value_factor", "random_score", "gauss",
        "linear", "exp", "script_score");

    private static final Set<String> KNOWN_FIELDS = Set.of("query", "functions", "score_mode", "boost_mode", "max_boost",
        "min_score", "weight", "field_value_factor", "random_score", "gauss", "linear", "exp", "script_score", "boost", "_name");

    @SuppressWarnings("unchecked")
    public static FunctionScoreQueryBuilder fromMap(Map<String, Object> value) {
        Object queryObj = value.remove("query");
        QueryBuilder query = queryObj == null ? null : QueryParser.parseQuery((Map<String, Object>) queryObj);
        FunctionScoreQueryBuilder builder = new FunctionScoreQueryBuilder(query);
        Object functions = value.remove("functions");
        if (functions != null) {
            for (Object o : QueryParseUtils.asList(functions, NAME)) {
                builder.functions.add(parseFilterFunction(QueryParseUtils.asMap(o, NAME)));
            }
        }
        List<String> remainingFunctionKeys = new ArrayList<>();
        for (String key : value.keySet()) {
            if (FUNCTION_TYPES.contains(key)) {
                remainingFunctionKeys.add(key);
            }
        }
        if (!remainingFunctionKeys.isEmpty()) {
            Map<String, Object> singleFunction = new LinkedHashMap<>();
            for (String key : remainingFunctionKeys) {
                singleFunction.put(key, value.remove(key));
            }
            builder.functions.add(parseFilterFunction(singleFunction));
        }
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "score_mode" -> builder.scoreMode = ScoreMode.fromString(QueryParseUtils.asString(e.getValue()));
                case "boost_mode" -> builder.boostMode = BoostMode.fromString(QueryParseUtils.asString(e.getValue()));
                case "max_boost" -> builder.maxBoost = QueryParseUtils.asFloat(e.getValue());
                case "min_score" -> builder.minScore = QueryParseUtils.asFloat(e.getValue());
                case "boost", "_name" -> builder.readCommon(e.getKey(), e.getValue());
                default -> throw QueryParseUtils.unknownField(NAME, e.getKey(), KNOWN_FIELDS);
            }
        }
        return builder;
    }

    @SuppressWarnings("unchecked")
    private static FilterFunctionBuilder parseFilterFunction(Map<String, Object> map) {
        Object filterObj = map.remove("filter");
        Object weightObj = map.remove("weight");
        QueryBuilder filter = filterObj == null ? null : QueryParser.parseQuery((Map<String, Object>) filterObj);
        ScoreFunctionBuilder function = null;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            function = switch (key) {
                case "weight" -> new WeightScoreFunction(QueryParseUtils.asFloat(e.getValue()));
                case "field_value_factor" -> FieldValueFactorScoreFunction.fromMap(QueryParseUtils.asMap(e.getValue(), key));
                case "random_score" -> RandomScoreFunction.fromMap(QueryParseUtils.asMap(e.getValue(), key));
                case "gauss" -> DecayScoreFunction.fromMap(DecayScoreFunction.DecayType.GAUSS, QueryParseUtils.asMap(e.getValue(), key));
                case "linear" -> DecayScoreFunction.fromMap(DecayScoreFunction.DecayType.LINEAR, QueryParseUtils.asMap(e.getValue(), key));
                case "exp" -> DecayScoreFunction.fromMap(DecayScoreFunction.DecayType.EXP, QueryParseUtils.asMap(e.getValue(), key));
                case "script_score" -> new ScriptScoreFunction(Script.parse(QueryParseUtils.asMap(e.getValue(), key).get("script")));
                default -> throw QueryParseUtils.unknownField("function_score", key, FUNCTION_TYPES);
            };
        }
        if (function == null) {
            if (weightObj == null) {
                throw QueryParseUtils.error("function_score function requires a function type");
            }
            return new FilterFunctionBuilder(filter, new WeightScoreFunction(QueryParseUtils.asFloat(weightObj)));
        }
        if (weightObj != null) {
            return new FilterFunctionBuilder(filter, function, QueryParseUtils.asFloat(weightObj));
        }
        return new FilterFunctionBuilder(filter, function);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public QueryBuilder query() {
        return query;
    }

    public List<FilterFunctionBuilder> functions() {
        return functions;
    }

    public ScoreMode scoreMode() {
        return scoreMode;
    }

    public BoostMode boostMode() {
        return boostMode;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("query", query.toMap());
        if (functions.size() == 1) {
            inner.putAll(functions.get(0).toMap());
        } else if (!functions.isEmpty()) {
            inner.put("functions", functions.stream().map(FilterFunctionBuilder::toMap).collect(Collectors.toList()));
        }
        if (scoreMode != ScoreMode.MULTIPLY) {
            inner.put("score_mode", scoreMode.toValue());
        }
        if (boostMode != BoostMode.MULTIPLY) {
            inner.put("boost_mode", boostMode.toValue());
        }
        if (maxBoost != null) {
            inner.put("max_boost", maxBoost);
        }
        if (minScore != null) {
            inner.put("min_score", minScore);
        }
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FunctionScoreQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && query.equals(other.query) && functions.equals(other.functions) && scoreMode == other.scoreMode
            && boostMode == other.boostMode && Objects.equals(maxBoost, other.maxBoost) && Objects.equals(minScore, other.minScore);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), query, functions, scoreMode, boostMode, maxBoost, minScore);
    }
}
