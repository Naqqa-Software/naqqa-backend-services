package com.naqqa.elasticsearch.script.painless;

import com.naqqa.elasticsearch.script.CompiledScript;
import com.naqqa.elasticsearch.script.Emitter;

import java.util.Map;

public final class PainlessCompiledScript implements CompiledScript {

    private final Interpreter interpreter;

    public PainlessCompiledScript(Interpreter interpreter) {
        this.interpreter = interpreter;
    }

    @Override
    public Object execute(Map<String, Object> variables, Emitter emitter) {
        return interpreter.execute(variables, emitter);
    }
}
