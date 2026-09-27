package com.naqqa.elasticsearch.script.mustache;

import com.naqqa.elasticsearch.script.CompiledScript;
import com.naqqa.elasticsearch.script.ScriptContext;
import com.naqqa.elasticsearch.script.ScriptEngine;

import java.util.Map;

public final class MustacheScriptEngine implements ScriptEngine {

    @Override
    public String type() {
        return "mustache";
    }

    @Override
    public CompiledScript compile(String name, String source, ScriptContext context, Map<String, String> options) {
        boolean jsonEscape = options == null || !"text/plain".equals(options.get("content_type"));
        MustacheParser.parse(source);
        return new MustacheCompiledScript(source, jsonEscape, Map.of());
    }

    @Override
    public boolean supports(ScriptContext context) {
        return context.returnType() == ScriptContext.ReturnType.STRING || context.returnType() == ScriptContext.ReturnType.OBJECT;
    }
}
