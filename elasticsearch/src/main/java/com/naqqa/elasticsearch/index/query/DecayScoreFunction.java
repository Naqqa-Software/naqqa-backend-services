package com.naqqa.elasticsearch.index.query;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class DecayScoreFunction implements ScoreFunctionBuilder {

    public enum DecayType {
        GAUSS, LINEAR, EXP;

        static DecayType fromString(String s) {
            return DecayType.valueOf(s.toUpperCase(Locale.ROOT));
        }

        String toValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final DecayType decayType;
    private final String field;
    private Object origin;
    private Object scale;
    private Object offset;
    private double decay = 0.5;
    private String multiValueMode;

    public DecayScoreFunction(DecayType decayType, String field, Object scale) {
        this.decayType = Objects.requireNonNull(decayType);
        this.field = Objects.requireNonNull(field);
        this.scale = Objects.requireNonNull(scale);
    }

    public static DecayScoreFunction fromMap(DecayType decayType, Map<String, Object> value) {
        Object multiValueMode = value.remove("multi_value_mode");
        Map.Entry<String, Object> field = QueryParseUtils.singleField(decayType.toValue(), value);
        Map<String, Object> params = QueryParseUtils.asMap(field.getValue(), decayType.toValue());
        Object scale = params.remove("scale");
        if (scale == null) {
            throw QueryParseUtils.error("[{}] requires a [scale]", decayType.toValue());
        }
        DecayScoreFunction fn = new DecayScoreFunction(decayType, field.getKey(), scale);
        Object origin = params.remove("origin");
        if (origin != null) {
            fn.origin = origin;
        }
        Object offset = params.remove("offset");
        if (offset != null) {
            fn.offset = offset;
        }
        Object decay = params.remove("decay");
        if (decay != null) {
            fn.decay = QueryParseUtils.asDouble(decay);
        }
        if (!params.isEmpty()) {
            throw QueryParseUtils.error("[{}] unknown field [{}]", decayType.toValue(), params.keySet().iterator().next());
        }
        if (multiValueMode != null) {
            fn.multiValueMode = QueryParseUtils.asString(multiValueMode);
        }
        return fn;
    }

    public DecayType decayType() {
        return decayType;
    }

    public String field() {
        return field;
    }

    public Object scale() {
        return scale;
    }

    public Object origin() {
        return origin;
    }

    @Override
    public String getName() {
        return decayType.toValue();
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> params = new LinkedHashMap<>();
        if (origin != null) {
            params.put("origin", origin);
        }
        params.put("scale", scale);
        if (offset != null) {
            params.put("offset", offset);
        }
        if (decay != 0.5) {
            params.put("decay", decay);
        }
        Map<String, Object> fieldMap = new LinkedHashMap<>();
        fieldMap.put(field, params);
        if (multiValueMode != null) {
            fieldMap.put("multi_value_mode", multiValueMode);
        }
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put(decayType.toValue(), fieldMap);
        return outer;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DecayScoreFunction other)) {
            return false;
        }
        return decayType == other.decayType && field.equals(other.field) && Objects.equals(origin, other.origin)
            && scale.equals(other.scale) && Objects.equals(offset, other.offset) && decay == other.decay
            && Objects.equals(multiValueMode, other.multiValueMode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(decayType, field, origin, scale, offset, decay, multiValueMode);
    }
}
