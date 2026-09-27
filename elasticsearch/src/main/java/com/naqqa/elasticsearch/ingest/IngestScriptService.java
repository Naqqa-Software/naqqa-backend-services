package com.naqqa.elasticsearch.ingest;

import java.util.Map;

public interface IngestScriptService {

    Condition compileCondition(String source, String lang, Map<String, Object> params);

    ScriptExecutor compileScript(String source, String lang, Map<String, Object> params);

    @FunctionalInterface
    interface Condition {
        boolean test(IngestDocument document);
    }

    @FunctionalInterface
    interface ScriptExecutor {
        void execute(IngestDocument document) throws Exception;
    }
}
