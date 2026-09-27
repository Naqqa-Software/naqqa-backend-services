package com.naqqa.elasticsearch.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IngestService {

    private final PipelineStore pipelineStore;
    private final ProcessorRegistry registry;

    public IngestService(PipelineStore pipelineStore, ProcessorRegistry registry) {
        this.pipelineStore = pipelineStore;
        this.registry = registry;
    }

    public PipelineStore getPipelineStore() {
        return pipelineStore;
    }

    public ProcessorRegistry getRegistry() {
        return registry;
    }

    public IngestDocument executePipelines(List<String> pipelineIds, IngestDocument document) throws Exception {
        IngestDocument current = document;
        for (String id : pipelineIds) {
            Pipeline pipeline = pipelineStore.get(id);
            if (pipeline == null) {
                throw new ConfigurationException("pipeline with id [" + id + "] does not exist");
            }
            current = pipeline.execute(current);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    public static IngestDocument toIngestDocument(Map<String, Object> docMap) {
        String index = (String) docMap.get("_index");
        String id = (String) docMap.get("_id");
        String routing = (String) docMap.get("_routing");
        Object versionRaw = docMap.get("_version");
        Long version = versionRaw == null ? null : ((Number) versionRaw).longValue();
        String versionType = (String) docMap.get("_version_type");
        Map<String, Object> source = docMap.get("_source") instanceof Map ? (Map<String, Object>) docMap.get("_source") : new LinkedHashMap<>();
        Map<String, Object> sourceCopy = (Map<String, Object>) IngestDocument.deepCopy(source);
        return new IngestDocument(index, id, routing, version, versionType, sourceCopy);
    }

    public Map<String, Object> simulate(Pipeline pipeline, List<Map<String, Object>> rawDocs, boolean verbose) {
        List<Object> docResults = new ArrayList<>();
        for (Map<String, Object> rawDoc : rawDocs) {
            docResults.add(simulateDocument(pipeline, toIngestDocument(rawDoc), verbose));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("docs", docResults);
        return response;
    }

    private Map<String, Object> simulateDocument(Pipeline pipeline, IngestDocument document, boolean verbose) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        if (!verbose) {
            try {
                IngestDocument result = pipeline.execute(document);
                if (result == null) {
                    Map<String, Object> doc = document.toSimulateMap();
                    doc.put("_id", document.getId());
                    wrapper.put("doc", doc);
                } else {
                    wrapper.put("doc", result.toSimulateMap());
                }
            } catch (Exception e) {
                wrapper.put("doc", document.toSimulateMap());
                wrapper.put("error", errorMap(e));
            }
            return wrapper;
        }

        List<Object> processorResults = new ArrayList<>();
        IngestDocument current = document;
        try {
            for (Processor p : pipeline.getProcessors()) {
                Map<String, Object> pr = new LinkedHashMap<>();
                pr.put("processor_type", p.getType());
                if (p.getTag() != null) {
                    pr.put("tag", p.getTag());
                }
                if (p instanceof ConditionalProcessor cp && !cp.testCondition(current)) {
                    pr.put("status", "skipped");
                    processorResults.add(pr);
                    continue;
                }
                try {
                    IngestDocument result = p.execute(current);
                    if (result == null) {
                        pr.put("status", "dropped");
                        processorResults.add(pr);
                        current = null;
                        break;
                    }
                    current = result;
                    pr.put("doc", current.toSimulateMap());
                    pr.put("status", "success");
                    processorResults.add(pr);
                } catch (Exception e) {
                    pr.put("status", "error");
                    pr.put("error", errorMap(e));
                    processorResults.add(pr);
                    throw e;
                }
            }
            wrapper.put("processor_results", processorResults);
        } catch (Exception e) {
            wrapper.put("processor_results", processorResults);
            wrapper.put("error", errorMap(e));
        }
        return wrapper;
    }

    private static Map<String, Object> errorMap(Exception e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", e.getClass().getSimpleName());
        error.put("reason", e.getMessage());
        return error;
    }
}
