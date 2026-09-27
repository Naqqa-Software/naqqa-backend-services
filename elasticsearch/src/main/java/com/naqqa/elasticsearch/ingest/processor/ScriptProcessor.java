package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.IngestScriptService;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ScriptProcessor extends AbstractProcessor {

    public static final String TYPE = "script";

    private final IngestScriptService.ScriptExecutor executor;

    public ScriptProcessor(String tag, String description, IngestScriptService.ScriptExecutor executor) {
        super(TYPE, tag, description);
        this.executor = executor;
    }

    @Override
    public IngestDocument execute(IngestDocument document) throws Exception {
        executor.execute(document);
        return document;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        @SuppressWarnings("unchecked")
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String source = ConfigurationUtils.readOptionalStringProperty(config, "source");
            String id = ConfigurationUtils.readOptionalStringProperty(config, "id");
            String lang = ConfigurationUtils.readStringProperty(TYPE, tag, config, "lang", "painless");
            Map<String, Object> params = ConfigurationUtils.readMap(TYPE, tag, config, "params", new LinkedHashMap<>());
            String effectiveSource = source != null ? source : id;
            if (effectiveSource == null) {
                throw ConfigurationUtils.newConfigurationException(TYPE, tag, "source", "either [source] or [id] must be specified");
            }
            IngestScriptService scriptService = registry.getScriptService();
            IngestScriptService.ScriptExecutor executor = scriptService != null
                ? scriptService.compileScript(effectiveSource, lang, params)
                : doc -> { };
            return new ScriptProcessor(tag, description, executor);
        }
    }
}
