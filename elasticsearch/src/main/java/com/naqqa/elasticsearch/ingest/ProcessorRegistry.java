package com.naqqa.elasticsearch.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProcessorRegistry {

    private final Map<String, Processor.Factory> factories = new LinkedHashMap<>();
    private final PipelineStore pipelineStore;
    private final IngestScriptService scriptService;
    private final EnrichLookupService enrichLookupService;

    public ProcessorRegistry(PipelineStore pipelineStore, IngestScriptService scriptService, EnrichLookupService enrichLookupService) {
        this.pipelineStore = pipelineStore;
        this.scriptService = scriptService;
        this.enrichLookupService = enrichLookupService;
    }

    public void register(String type, Processor.Factory factory) {
        factories.put(type, factory);
    }

    public Processor.Factory getFactory(String type) {
        Processor.Factory f = factories.get(type);
        if (f == null) {
            throw new ConfigurationException("no processor type exists with name [" + type + "]");
        }
        return f;
    }

    public PipelineStore getPipelineStore() {
        return pipelineStore;
    }

    public IngestScriptService getScriptService() {
        return scriptService;
    }

    public EnrichLookupService getEnrichLookupService() {
        return enrichLookupService;
    }

    @SuppressWarnings("unchecked")
    public Processor buildProcessor(Map<String, Object> processorConfig) {
        if (processorConfig.size() != 1) {
            throw new ConfigurationException("processor config must have exactly one key describing the processor type, got: " + processorConfig.keySet());
        }
        Map.Entry<String, Object> entry = processorConfig.entrySet().iterator().next();
        String type = entry.getKey();
        Map<String, Object> rawConfig = entry.getValue() == null ? new LinkedHashMap<>() : new LinkedHashMap<>((Map<String, Object>) entry.getValue());

        String tag = ConfigurationUtils.readOptionalStringProperty(rawConfig, "tag");
        String description = ConfigurationUtils.readOptionalStringProperty(rawConfig, "description");
        String ifSource = ConfigurationUtils.readOptionalStringProperty(rawConfig, "if");
        List<Map<String, Object>> onFailureConfigs = ConfigurationUtils.readList(type, tag, rawConfig, "on_failure");
        boolean ignoreFailure = ConfigurationUtils.readBooleanProperty(type, tag, rawConfig, "ignore_failure", false);

        Processor.Factory factory = getFactory(type);
        Processor inner;
        try {
            inner = factory.create(this, tag, description, rawConfig);
        } catch (Exception e) {
            if (e instanceof ConfigurationException ce) {
                throw ce;
            }
            throw new ConfigurationException("failed to build processor [" + type + "]: " + e.getMessage(), e);
        }
        ConfigurationUtils.assertNoLeftovers(type, tag, rawConfig);

        List<Processor> onFailureProcessors = buildProcessors(onFailureConfigs);
        Processor compound = new CompoundProcessor(inner, onFailureProcessors, ignoreFailure);

        if (ifSource != null) {
            IngestScriptService.Condition condition = scriptService != null
                ? scriptService.compileCondition(ifSource, "painless", Map.of())
                : doc -> true;
            return new ConditionalProcessor(compound, condition);
        }
        return compound;
    }

    public List<Processor> buildProcessors(List<Map<String, Object>> configs) {
        List<Processor> result = new ArrayList<>();
        for (Map<String, Object> config : configs) {
            result.add(buildProcessor(config));
        }
        return result;
    }
}
