package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.Map;

public final class DropProcessor extends AbstractProcessor {

    public static final String TYPE = "drop";

    public DropProcessor(String tag, String description) {
        super(TYPE, tag, description);
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        return null;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            return new DropProcessor(tag, description);
        }
    }
}
