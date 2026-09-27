package com.naqqa.elasticsearch.script.mustache;

import com.naqqa.elasticsearch.script.CompiledScript;
import com.naqqa.elasticsearch.script.Emitter;

import java.util.Map;

public final class MustacheCompiledScript implements CompiledScript {

    private final String template;
    private final boolean jsonEscape;
    private final Map<String, String> partials;

    public MustacheCompiledScript(String template, boolean jsonEscape, Map<String, String> partials) {
        this.template = template;
        this.jsonEscape = jsonEscape;
        this.partials = partials;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> variables, Emitter emitter) {
        Object context = variables;
        if (variables != null && variables.size() == 1 && variables.get("params") instanceof Map<?, ?> params) {
            context = params;
        }
        return new MustacheRenderer(partials, jsonEscape).renderTemplate(template, context);
    }
}
