package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Pipeline;
import com.naqqa.elasticsearch.ingest.PipelineStore;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PipelineProcessor extends AbstractProcessor {

    public static final String TYPE = "pipeline";
    private static final String STACK_KEY = "pipeline_stack";

    private final String pipelineId;
    private final boolean ignoreMissingPipeline;
    private final PipelineStore pipelineStore;

    public PipelineProcessor(String tag, String description, String pipelineId, boolean ignoreMissingPipeline, PipelineStore pipelineStore) {
        super(TYPE, tag, description);
        this.pipelineId = pipelineId;
        this.ignoreMissingPipeline = ignoreMissingPipeline;
        this.pipelineStore = pipelineStore;
    }

    @Override
    @SuppressWarnings("unchecked")
    public IngestDocument execute(IngestDocument document) throws Exception {
        String resolved = document.renderTemplate(pipelineId);
        Pipeline pipeline = pipelineStore.get(resolved);
        if (pipeline == null) {
            if (ignoreMissingPipeline) {
                return document;
            }
            throw new IllegalArgumentException("Pipeline processor configured for non-existent pipeline [" + resolved + "]");
        }
        List<String> stack = (List<String>) document.getIngestMetadata().computeIfAbsent(STACK_KEY, k -> new ArrayList<String>());
        if (stack.contains(resolved)) {
            throw new IllegalStateException("Cycle detected for pipeline: " + resolved);
        }
        stack.add(resolved);
        try {
            return pipeline.execute(document);
        } finally {
            stack.remove(stack.size() - 1);
        }
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String pipelineId = ConfigurationUtils.readStringProperty(TYPE, tag, config, "name");
            boolean ignoreMissingPipeline = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_missing_pipeline", false);
            return new PipelineProcessor(tag, description, pipelineId, ignoreMissingPipeline, registry.getPipelineStore());
        }
    }
}
