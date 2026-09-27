package com.naqqa.elasticsearch.action.write;

import java.util.Map;

@FunctionalInterface
public interface UpdateScriptExecutor {

    Map<String, Object> execute(Map<String, Object> scriptDefinition, Map<String, Object> currentSource);
}
