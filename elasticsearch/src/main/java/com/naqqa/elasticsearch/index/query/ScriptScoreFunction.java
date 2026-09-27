package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.script.Script;

import java.util.Map;
import java.util.Objects;

public final class ScriptScoreFunction implements ScoreFunctionBuilder {

    public static final String NAME = "script_score";

    private final Script script;

    public ScriptScoreFunction(Script script) {
        this.script = Objects.requireNonNull(script);
    }

    public Script script() {
        return script;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Map<String, Object> toMap() {
        return Map.of(NAME, script.toMap());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ScriptScoreFunction other && script.equals(other.script);
    }

    @Override
    public int hashCode() {
        return Objects.hash(script);
    }
}
