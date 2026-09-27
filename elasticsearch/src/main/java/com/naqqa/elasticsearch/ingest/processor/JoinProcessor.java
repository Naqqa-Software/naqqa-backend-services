package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class JoinProcessor extends AbstractProcessor {

    public static final String TYPE = "join";

    private final String field;
    private final String separator;
    private final String targetField;

    public JoinProcessor(String tag, String description, String field, String separator, String targetField) {
        super(TYPE, tag, description);
        this.field = field;
        this.separator = separator;
        this.targetField = targetField;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        Object value = document.getFieldValue(resolvedField, Object.class);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("field [" + resolvedField + "] of type [" + (value == null ? "null" : value.getClass().getName()) + "] cannot be joined");
        }
        String joined = list.stream().map(String::valueOf).collect(Collectors.joining(separator));
        document.setFieldValue(document.renderTemplate(targetField), joined);
        return document;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String separator = ConfigurationUtils.readStringProperty(TYPE, tag, config, "separator");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field", field);
            return new JoinProcessor(tag, description, field, separator, targetField);
        }
    }
}
