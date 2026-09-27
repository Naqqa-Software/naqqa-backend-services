package com.naqqa.elasticsearch.ingest;

import java.util.List;
import java.util.Map;

public final class Pipeline {

    private final String id;
    private final String description;
    private final Integer version;
    private final Map<String, Object> meta;
    private final List<Processor> processors;
    private final List<Processor> onFailureProcessors;

    public Pipeline(String id, String description, Integer version, Map<String, Object> meta,
                     List<Processor> processors, List<Processor> onFailureProcessors) {
        this.id = id;
        this.description = description;
        this.version = version;
        this.meta = meta;
        this.processors = processors;
        this.onFailureProcessors = onFailureProcessors;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public Integer getVersion() {
        return version;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    public List<Processor> getProcessors() {
        return processors;
    }

    public List<Processor> getOnFailureProcessors() {
        return onFailureProcessors;
    }

    public IngestDocument execute(IngestDocument document) throws Exception {
        try {
            IngestDocument current = document;
            for (Processor p : processors) {
                IngestDocument result = p.execute(current);
                if (result == null) {
                    current.setDropped(true);
                    return null;
                }
                current = result;
            }
            return current;
        } catch (Exception e) {
            if (!onFailureProcessors.isEmpty()) {
                document.setFieldValue(IngestDocument.INGEST_KEY + "." + IngestDocument.ON_FAILURE_PIPELINE, id);
                document.setFieldValue(IngestDocument.INGEST_KEY + "." + IngestDocument.ON_FAILURE_MESSAGE, e.getMessage());
                IngestDocument current = document;
                for (Processor p : onFailureProcessors) {
                    IngestDocument result = p.execute(current);
                    if (result == null) {
                        current.setDropped(true);
                        return null;
                    }
                    current = result;
                }
                return current;
            }
            throw e;
        }
    }
}
