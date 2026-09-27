package com.naqqa.elasticsearch.script;

import java.util.Map;

public interface CompiledScript {

    Object execute(Map<String, Object> variables, Emitter emitter);

    default Object execute(Map<String, Object> variables) {
        return execute(variables, null);
    }
}
