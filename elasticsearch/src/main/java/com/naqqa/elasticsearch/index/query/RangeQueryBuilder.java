package com.naqqa.elasticsearch.index.query;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RangeQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "range";

    public enum Relation {
        INTERSECTS, CONTAINS, WITHIN;

        static Relation fromString(String s) {
            return Relation.valueOf(s.toUpperCase(Locale.ROOT));
        }

        String toValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final String fieldName;
    private Object from;
    private Object to;
    private boolean includeLower = true;
    private boolean includeUpper = true;
    private String format;
    private String timeZone;
    private Relation relation = Relation.INTERSECTS;

    public RangeQueryBuilder(String fieldName) {
        this.fieldName = Objects.requireNonNull(fieldName);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("gte", "gt", "lte", "lt", "from", "to", "include_lower",
        "include_upper", "format", "time_zone", "relation", "boost", "_name");

    public static RangeQueryBuilder fromMap(Map<String, Object> value) {
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        Map<String, Object> params = QueryParseUtils.asMap(field.getValue(), NAME);
        RangeQueryBuilder builder = new RangeQueryBuilder(field.getKey());
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            switch (key) {
                case "gte", "from" -> {
                    builder.from = v;
                    builder.includeLower = true;
                }
                case "gt" -> {
                    builder.from = v;
                    builder.includeLower = false;
                }
                case "lte", "to" -> {
                    builder.to = v;
                    builder.includeUpper = true;
                }
                case "lt" -> {
                    builder.to = v;
                    builder.includeUpper = false;
                }
                case "include_lower" -> builder.includeLower = QueryParseUtils.asBoolean(v);
                case "include_upper" -> builder.includeUpper = QueryParseUtils.asBoolean(v);
                case "format" -> builder.format = QueryParseUtils.asString(v);
                case "time_zone" -> builder.timeZone = QueryParseUtils.asString(v);
                case "relation" -> builder.relation = Relation.fromString(QueryParseUtils.asString(v));
                case "boost", "_name" -> builder.readCommon(key, v);
                default -> throw QueryParseUtils.unknownField(NAME, key, KNOWN_FIELDS);
            }
        }
        return builder;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public String fieldName() {
        return fieldName;
    }

    public RangeQueryBuilder gte(Object value) {
        from = value;
        includeLower = true;
        return this;
    }

    public RangeQueryBuilder gt(Object value) {
        from = value;
        includeLower = false;
        return this;
    }

    public RangeQueryBuilder lte(Object value) {
        to = value;
        includeUpper = true;
        return this;
    }

    public RangeQueryBuilder lt(Object value) {
        to = value;
        includeUpper = false;
        return this;
    }

    public Object from() {
        return from;
    }

    public Object to() {
        return to;
    }

    public boolean includeLower() {
        return includeLower;
    }

    public boolean includeUpper() {
        return includeUpper;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (from != null) {
            params.put(includeLower ? "gte" : "gt", from);
        }
        if (to != null) {
            params.put(includeUpper ? "lte" : "lt", to);
        }
        if (format != null) {
            params.put("format", format);
        }
        if (timeZone != null) {
            params.put("time_zone", timeZone);
        }
        if (relation != Relation.INTERSECTS) {
            params.put("relation", relation.toValue());
        }
        writeCommon(params);
        inner.put(fieldName, params);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RangeQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName) && Objects.equals(from, other.from) && Objects.equals(to, other.to)
            && includeLower == other.includeLower && includeUpper == other.includeUpper && Objects.equals(format, other.format)
            && Objects.equals(timeZone, other.timeZone) && relation == other.relation;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, from, to, includeLower, includeUpper, format, timeZone, relation);
    }
}
