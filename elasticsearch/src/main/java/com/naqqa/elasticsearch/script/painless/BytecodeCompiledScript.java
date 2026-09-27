package com.naqqa.elasticsearch.script.painless;

import com.naqqa.elasticsearch.script.CompiledScript;
import com.naqqa.elasticsearch.script.Emitter;

import java.util.Map;

public final class BytecodeCompiledScript implements CompiledScript {

    private final BytecodeExecutable executable;

    public BytecodeCompiledScript(BytecodeExecutable executable) {
        this.executable = executable;
    }

    @Override
    public Object execute(Map<String, Object> variables, Emitter emitter) {
        return executable.exec(variables);
    }
}
