package com.naqqa.elasticsearch.ingest;

import java.util.List;
import java.util.Map;

public final class PipelineFactory {

    private PipelineFactory() {
    }

    @SuppressWarnings("unchecked")
    public static Pipeline create(String id, Map<String, Object> config, ProcessorRegistry registry) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(config);
        String description = ConfigurationUtils.readOptionalStringProperty(copy, "description");
        Integer version = null;
        Object versionRaw = copy.remove("version");
        if (versionRaw != null) {
            version = ((Number) versionRaw).intValue();
        }
        Map<String, Object> meta = ConfigurationUtils.readOptionalMap(copy, "_meta");
        Object processorsRaw = copy.remove("processors");
        if (processorsRaw == null) {
            throw new ConfigurationException("[" + id + "] pipeline must specify a non-empty [processors] list");
        }
        List<Map<String, Object>> processorConfigs = (List<Map<String, Object>>) processorsRaw;
        List<Processor> processors = registry.buildProcessors(processorConfigs);

        List<Map<String, Object>> onFailureConfigs = ConfigurationUtils.readList(id, null, copy, "on_failure");
        List<Processor> onFailureProcessors = registry.buildProcessors(onFailureConfigs);

        return new Pipeline(id, description, version, meta, processors, onFailureProcessors);
    }
}
