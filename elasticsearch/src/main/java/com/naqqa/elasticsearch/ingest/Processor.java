package com.naqqa.elasticsearch.ingest;

import java.util.Map;

public interface Processor {

    IngestDocument execute(IngestDocument document) throws Exception;

    String getType();

    String getTag();

    String getDescription();

    @FunctionalInterface
    interface Factory {
        Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) throws Exception;
    }
}
