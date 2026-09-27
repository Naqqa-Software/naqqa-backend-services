package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.script.Script;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ScriptQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "script";

    private final Script script;

    public ScriptQueryBuilder(Script script) {
        this.script = Objects.requireNonNull(script);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("script", "boost", "_name");

    public static ScriptQueryBuilder fromMap(Map<String, Object> value) {
        Object script = value.remove("script");
        if (script == null) {
            throw QueryParseUtils.error("[{}] requires a [script]", NAME);
        }
        ScriptQueryBuilder builder = new ScriptQueryBuilder(Script.parse(script));
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "boost", "_name" -> builder.readCommon(e.getKey(), e.getValue());
                default -> throw QueryParseUtils.unknownField(NAME, e.getKey(), KNOWN_FIELDS);
            }
        }
        return builder;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public Script script() {
        return script;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("script", script.toMap());
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ScriptQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && script.equals(other.script);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), script);
    }
}
