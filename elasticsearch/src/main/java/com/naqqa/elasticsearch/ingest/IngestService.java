package com.naqqa.elasticsearch.ingest;

import com.naqqa.elasticsearch.cluster.service.ClusterChangedEvent;
import com.naqqa.elasticsearch.cluster.service.ClusterStateListener;
import com.naqqa.elasticsearch.cluster.state.MapCustom;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.node.cluster.ClusterStateManager;
import com.naqqa.elasticsearch.rest.support.RestApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class IngestService implements ClusterStateListener {

    public static final String PIPELINES_CUSTOM = "ingest_pipelines";

    private final PipelineStore pipelineStore;
    private final ProcessorRegistry registry;
    private volatile ClusterStateManager clusterStateManager;

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

    public void bind(ClusterStateManager clusterStateManager) {
        this.clusterStateManager = clusterStateManager;
        syncFromClusterState(clusterStateManager.state().getMetadata());
        clusterStateManager.addListener(this);
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        if (event.state().getMetadata() != event.previousState().getMetadata()) {
            syncFromClusterState(event.state().getMetadata());
        }
    }

    private void syncFromClusterState(Metadata metadata) {
        pipelineStore.syncFrom(metadata.mapCustom(PIPELINES_CUSTOM).asMap(), registry);
    }

    public void putPipeline(String id, Map<String, Object> config) {
        PipelineFactory.create(id, new LinkedHashMap<>(config), registry);
        mutate("put-pipeline [" + id + "]", md -> md.toBuilder().mutateMapCustom(PIPELINES_CUSTOM, mc -> mc.with(id, config)).build());
        pipelineStore.put(id, config, registry);
    }

    public void deletePipeline(String id) {
        if (pipelineStore.get(id) == null) {
            throw new RestApiException(404, "pipeline [" + id + "] is missing");
        }
        mutate("delete-pipeline [" + id + "]", md -> md.toBuilder().mutateMapCustom(PIPELINES_CUSTOM, mc -> mc.without(id)).build());
        pipelineStore.delete(id);
    }

    private void mutate(String source, java.util.function.UnaryOperator<Metadata> op) {
        if (clusterStateManager == null) {
            return;
        }
        try {
            clusterStateManager.submit(source, cs -> cs.builder().metadata(op.apply(cs.getMetadata())).build())
                .get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RestApiException(500, cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new RestApiException(503, "timed out waiting for cluster state update [" + source + "]");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RestApiException(500, "interrupted");
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RestApiException(500, cause.getMessage(), cause);
        }
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
