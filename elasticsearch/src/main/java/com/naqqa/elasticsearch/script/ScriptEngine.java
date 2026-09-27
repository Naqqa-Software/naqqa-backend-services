package com.naqqa.elasticsearch.script;

import java.util.Map;

public interface ScriptEngine {

    String type();

    CompiledScript compile(String name, String source, ScriptContext context, Map<String, String> options);

    default boolean supports(ScriptContext context) {
        return true;
    }

    default void validate(String source) {
        compile("validate", source, ScriptContext.FIELD, Map.of());
    }
}
