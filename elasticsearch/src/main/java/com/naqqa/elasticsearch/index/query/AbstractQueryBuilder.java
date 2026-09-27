package com.naqqa.elasticsearch.index.query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public abstract class AbstractQueryBuilder implements QueryBuilder {

    public static final float DEFAULT_BOOST = 1.0f;

    protected float boost = DEFAULT_BOOST;
    protected String name;

    public float boost() {
        return boost;
    }

    public void boost(float boost) {
        this.boost = boost;
    }

    public String queryName() {
        return name;
    }

    public void queryName(String name) {
        this.name = name;
    }

    protected void writeCommon(Map<String, Object> m) {
        if (boost != DEFAULT_BOOST) {
            m.put("boost", boost);
        }
        if (name != null) {
            m.put("_name", name);
        }
    }

    protected void readCommon(String key, Object value) {
        if ("boost".equals(key)) {
            boost = QueryParseUtils.asFloat(value);
        } else if ("_name".equals(key)) {
            name = QueryParseUtils.asString(value);
        } else {
            throw new IllegalArgumentException("not a common field: " + key);
        }
    }

    protected boolean isCommonField(String key) {
        return "boost".equals(key) || "_name".equals(key);
    }

    @Override
    public final Map<String, Object> toMap() {
        Map<String, Object> inner = new LinkedHashMap<>();
        doToInnerMap(inner);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put(getWriteableName(), inner);
        return outer;
    }

    protected abstract void doToInnerMap(Map<String, Object> inner);

    protected boolean commonEquals(AbstractQueryBuilder other) {
        return boost == other.boost && Objects.equals(name, other.name);
    }

    protected int commonHash() {
        return Objects.hash(boost, name);
    }
}
