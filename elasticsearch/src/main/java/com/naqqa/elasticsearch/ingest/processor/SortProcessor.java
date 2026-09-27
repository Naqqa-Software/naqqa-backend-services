package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class SortProcessor extends AbstractProcessor {

    public static final String TYPE = "sort";

    private final String field;
    private final String order;
    private final String targetField;

    public SortProcessor(String tag, String description, String field, String order, String targetField) {
        super(TYPE, tag, description);
        this.field = field;
        this.order = order;
        this.targetField = targetField;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        Object value = document.getFieldValue(resolvedField, Object.class);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("field [" + resolvedField + "] of type [" + (value == null ? "null" : value.getClass().getName()) + "] cannot be sorted, expected a list");
        }
        List<Comparable> sorted = new ArrayList<>((List<Comparable>) list);
        Collections.sort(sorted);
        if ("desc".equals(order)) {
            Collections.reverse(sorted);
        }
        document.setFieldValue(document.renderTemplate(targetField), sorted);
        return document;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String order = ConfigurationUtils.readStringProperty(TYPE, tag, config, "order", "asc");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field", field);
            if (!order.equals("asc") && !order.equals("desc")) {
                throw ConfigurationUtils.newConfigurationException(TYPE, tag, "order", "order must be [asc] or [desc]");
            }
            return new SortProcessor(tag, description, field, order, targetField);
        }
    }
}
