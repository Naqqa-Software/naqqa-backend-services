package com.naqqa.elasticsearch.script.painless;

import java.util.Map;

public interface BytecodeExecutable {

    Object exec(Map<String, Object> vars);
}
