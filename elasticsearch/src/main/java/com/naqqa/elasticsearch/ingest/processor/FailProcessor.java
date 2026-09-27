package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.Map;

public final class FailProcessor extends AbstractProcessor {

    public static final String TYPE = "fail";

    private final String message;

    public FailProcessor(String tag, String description, String message) {
        super(TYPE, tag, description);
        this.message = message;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        throw new FailProcessorException(document.renderTemplate(message));
    }

    public static final class FailProcessorException extends RuntimeException {
        public FailProcessorException(String message) {
            super(message);
        }
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String message = ConfigurationUtils.readStringProperty(TYPE, tag, config, "message");
            return new FailProcessor(tag, description, message);
        }
    }
}
