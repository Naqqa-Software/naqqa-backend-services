package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.Map;

public final class RenameProcessor extends AbstractProcessor {

    public static final String TYPE = "rename";

    private final String field;
    private final String targetField;
    private final boolean ignoreMissing;

    public RenameProcessor(String tag, String description, String field, String targetField, boolean ignoreMissing) {
        super(TYPE, tag, description);
        this.field = field;
        this.targetField = targetField;
        this.ignoreMissing = ignoreMissing;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        if (!document.hasField(resolvedField)) {
            if (ignoreMissing) {
                return document;
            }
            throw new IllegalArgumentException("field [" + resolvedField + "] doesn't exist");
        }
        String resolvedTarget = document.renderTemplate(targetField);
        if (document.hasField(resolvedTarget)) {
            throw new IllegalArgumentException("field [" + resolvedTarget + "] already exists");
        }
        Object value = document.getFieldValue(resolvedField, Object.class);
        document.removeField(resolvedField);
        document.setFieldValue(resolvedTarget, value);
        return document;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field");
            boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_missing", false);
            return new RenameProcessor(tag, description, field, targetField, ignoreMissing);
        }
    }
}
