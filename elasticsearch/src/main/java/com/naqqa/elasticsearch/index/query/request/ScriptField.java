package com.naqqa.elasticsearch.index.query.request;

import com.naqqa.elasticsearch.index.query.QueryParseUtils;
import com.naqqa.elasticsearch.script.Script;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ScriptField {

    private final String name;
    private final Script script;
    private boolean ignoreFailure = false;

    public ScriptField(String name, Script script) {
        this.name = Objects.requireNonNull(name);
        this.script = Objects.requireNonNull(script);
    }

    public static ScriptField fromMap(String name, Map<String, Object> params) {
        Object script = params.remove("script");
        if (script == null) {
            throw QueryParseUtils.error("script_fields entry [{}] requires a [script]", name);
        }
        ScriptField field = new ScriptField(name, Script.parse(script));
        Object ignoreFailure = params.remove("ignore_failure");
        if (ignoreFailure != null) {
            field.ignoreFailure = QueryParseUtils.asBoolean(ignoreFailure);
        }
        return field;
    }

    public String name() {
        return name;
    }

    public Script script() {
        return script;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("script", script.toMap());
        if (ignoreFailure) {
            params.put("ignore_failure", true);
        }
        return params;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ScriptField other)) {
            return false;
        }
        return name.equals(other.name) && script.equals(other.script) && ignoreFailure == other.ignoreFailure;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, script, ignoreFailure);
    }
}
