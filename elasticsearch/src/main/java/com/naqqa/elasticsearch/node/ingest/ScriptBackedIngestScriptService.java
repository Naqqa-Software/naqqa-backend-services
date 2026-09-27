package com.naqqa.elasticsearch.node.ingest;

import com.naqqa.elasticsearch.ingest.IngestScriptService;
import com.naqqa.elasticsearch.script.Script;
import com.naqqa.elasticsearch.script.ScriptContext;
import com.naqqa.elasticsearch.script.ScriptService;
import com.naqqa.elasticsearch.script.ScriptType;

import java.util.Map;

public final class ScriptBackedIngestScriptService implements IngestScriptService {

    private final ScriptService scriptService;

    public ScriptBackedIngestScriptService(ScriptService scriptService) {
        this.scriptService = scriptService;
    }

    private static Script script(String source, String lang, Map<String, Object> params) {
        return new Script(ScriptType.INLINE, lang == null ? Script.DEFAULT_SCRIPT_LANG : lang, source, Map.of(),
            params == null ? Map.of() : params);
    }

    @Override
    public Condition compileCondition(String source, String lang, Map<String, Object> params) {
        Script script = script(source, lang, params);
        scriptService.compile(script, ScriptContext.PROCESSOR_CONDITIONAL);
        return document -> {
            Object result = scriptService.execute(script, ScriptContext.PROCESSOR_CONDITIONAL,
                Map.of("ctx", document.getSourceAndMetadata()));
            return Boolean.TRUE.equals(result);
        };
    }

    @Override
    public ScriptExecutor compileScript(String source, String lang, Map<String, Object> params) {
        Script script = script(source, lang, params);
        scriptService.compile(script, ScriptContext.INGEST);
        return document -> scriptService.execute(script, ScriptContext.INGEST, Map.of("ctx", document.getSourceAndMetadata()));
    }
}
