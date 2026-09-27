package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.common.unit.Fuzziness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class IntervalsQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "intervals";

    public sealed interface IntervalsSource permits Match, Prefix, Wildcard, Fuzzy, AllOf, AnyOf {
        Map<String, Object> toMap();
    }

    public record Filter(String type, IntervalsSource rule) {
        public Map<String, Object> toMap() {
            return Map.of(type, rule.toMap());
        }
    }

    public record Match(String query, Integer maxGaps, boolean ordered, String analyzer, Filter filter, String useField) implements IntervalsSource {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("query", query);
            if (maxGaps != null) {
                m.put("max_gaps", maxGaps);
            }
            if (ordered) {
                m.put("ordered", true);
            }
            if (analyzer != null) {
                m.put("analyzer", analyzer);
            }
            if (filter != null) {
                m.put("filter", filter.toMap());
            }
            if (useField != null) {
                m.put("use_field", useField);
            }
            return Map.of("match", m);
        }
    }

    public record Prefix(String prefix, String analyzer, String useField) implements IntervalsSource {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("prefix", prefix);
            if (analyzer != null) {
                m.put("analyzer", analyzer);
            }
            if (useField != null) {
                m.put("use_field", useField);
            }
            return Map.of("prefix", m);
        }
    }

    public record Wildcard(String pattern, String analyzer, String useField) implements IntervalsSource {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pattern", pattern);
            if (analyzer != null) {
                m.put("analyzer", analyzer);
            }
            if (useField != null) {
                m.put("use_field", useField);
            }
            return Map.of("wildcard", m);
        }
    }

    public record Fuzzy(String term, Fuzziness fuzziness, boolean transpositions, String analyzer, String useField) implements IntervalsSource {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("term", term);
            if (fuzziness != null) {
                m.put("fuzziness", fuzziness.asString());
            }
            if (!transpositions) {
                m.put("transpositions", false);
            }
            if (analyzer != null) {
                m.put("analyzer", analyzer);
            }
            if (useField != null) {
                m.put("use_field", useField);
            }
            return Map.of("fuzzy", m);
        }
    }

    public record AllOf(List<IntervalsSource> intervals, Integer maxGaps, boolean ordered, Filter filter) implements IntervalsSource {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intervals", intervals.stream().map(IntervalsSource::toMap).collect(Collectors.toList()));
            if (maxGaps != null) {
                m.put("max_gaps", maxGaps);
            }
            if (ordered) {
                m.put("ordered", true);
            }
            if (filter != null) {
                m.put("filter", filter.toMap());
            }
            return Map.of("all_of", m);
        }
    }

    public record AnyOf(List<IntervalsSource> intervals, Filter filter) implements IntervalsSource {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intervals", intervals.stream().map(IntervalsSource::toMap).collect(Collectors.toList()));
            if (filter != null) {
                m.put("filter", filter.toMap());
            }
            return Map.of("any_of", m);
        }
    }

    private final String fieldName;
    private final IntervalsSource rule;

    public IntervalsQueryBuilder(String fieldName, IntervalsSource rule) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.rule = Objects.requireNonNull(rule);
    }

    public static IntervalsQueryBuilder fromMap(Map<String, Object> value) {
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        Map<String, Object> params = QueryParseUtils.asMap(field.getValue(), NAME);
        IntervalsSource rule = parseSource(params);
        return new IntervalsQueryBuilder(field.getKey(), rule);
    }

    private static IntervalsSource parseSource(Map<String, Object> params) {
        Map.Entry<String, Object> entry = QueryParseUtils.singleField(NAME, params);
        String type = entry.getKey();
        Map<String, Object> body = QueryParseUtils.asMap(entry.getValue(), type);
        return switch (type) {
            case "match" -> parseMatch(body);
            case "prefix" -> new Prefix(QueryParseUtils.asString(body.remove("prefix")), strOrNull(body, "analyzer"), strOrNull(body, "use_field"));
            case "wildcard" -> new Wildcard(QueryParseUtils.asString(body.remove("pattern")), strOrNull(body, "analyzer"), strOrNull(body, "use_field"));
            case "fuzzy" -> parseFuzzy(body);
            case "all_of" -> parseAllOf(body);
            case "any_of" -> parseAnyOf(body);
            default -> throw QueryParseUtils.unknownField(NAME, type, Set.of("match", "prefix", "wildcard", "fuzzy", "all_of", "any_of"));
        };
    }

    private static Match parseMatch(Map<String, Object> body) {
        String query = QueryParseUtils.asString(body.remove("query"));
        Integer maxGaps = body.containsKey("max_gaps") ? QueryParseUtils.asInt(body.remove("max_gaps")) : null;
        boolean ordered = body.containsKey("ordered") && QueryParseUtils.asBoolean(body.remove("ordered"));
        String analyzer = strOrNull(body, "analyzer");
        Filter filter = parseFilter(body);
        String useField = strOrNull(body, "use_field");
        return new Match(query, maxGaps, ordered, analyzer, filter, useField);
    }

    private static Fuzzy parseFuzzy(Map<String, Object> body) {
        String term = QueryParseUtils.asString(body.remove("term"));
        Fuzziness fuzziness = body.containsKey("fuzziness") ? QueryParseUtils.asFuzziness(body.remove("fuzziness")) : Fuzziness.AUTO;
        boolean transpositions = !body.containsKey("transpositions") || QueryParseUtils.asBoolean(body.remove("transpositions"));
        return new Fuzzy(term, fuzziness, transpositions, strOrNull(body, "analyzer"), strOrNull(body, "use_field"));
    }

    private static AllOf parseAllOf(Map<String, Object> body) {
        List<IntervalsSource> intervals = new ArrayList<>();
        for (Object o : QueryParseUtils.asList(body.remove("intervals"), "all_of")) {
            intervals.add(parseNested(o));
        }
        Integer maxGaps = body.containsKey("max_gaps") ? QueryParseUtils.asInt(body.remove("max_gaps")) : null;
        boolean ordered = body.containsKey("ordered") && QueryParseUtils.asBoolean(body.remove("ordered"));
        Filter filter = parseFilter(body);
        return new AllOf(intervals, maxGaps, ordered, filter);
    }

    private static AnyOf parseAnyOf(Map<String, Object> body) {
        List<IntervalsSource> intervals = new ArrayList<>();
        for (Object o : QueryParseUtils.asList(body.remove("intervals"), "any_of")) {
            intervals.add(parseNested(o));
        }
        Filter filter = parseFilter(body);
        return new AnyOf(intervals, filter);
    }

    @SuppressWarnings("unchecked")
    private static IntervalsSource parseNested(Object o) {
        return parseSource(QueryParseUtils.asMap(o, NAME));
    }

    private static Filter parseFilter(Map<String, Object> body) {
        Object filterObj = body.remove("filter");
        if (filterObj == null) {
            return null;
        }
        Map<String, Object> filterMap = QueryParseUtils.asMap(filterObj, "filter");
        Map.Entry<String, Object> entry = QueryParseUtils.singleField("filter", filterMap);
        return new Filter(entry.getKey(), parseNested(entry.getValue()));
    }

    private static String strOrNull(Map<String, Object> body, String key) {
        Object v = body.remove(key);
        return v == null ? null : QueryParseUtils.asString(v);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public String fieldName() {
        return fieldName;
    }

    public IntervalsSource rule() {
        return rule;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        Map<String, Object> params = new LinkedHashMap<>(rule.toMap());
        writeCommon(params);
        inner.put(fieldName, params);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof IntervalsQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName) && rule.equals(other.rule);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, rule);
    }
}
