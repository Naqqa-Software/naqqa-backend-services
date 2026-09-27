package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class StringTransformProcessor extends AbstractProcessor {

    private final String field;
    private final String targetField;
    private final boolean ignoreMissing;
    private final Function<String, String> transform;

    public StringTransformProcessor(String type, String tag, String description, String field, String targetField,
                                     boolean ignoreMissing, Function<String, String> transform) {
        super(type, tag, description);
        this.field = field;
        this.targetField = targetField;
        this.ignoreMissing = ignoreMissing;
        this.transform = transform;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        if (!document.hasField(resolvedField)) {
            if (ignoreMissing) {
                return document;
            }
            throw new IllegalArgumentException("field [" + resolvedField + "] not present as part of path [" + resolvedField + "]");
        }
        Object value = document.getFieldValue(resolvedField, Object.class);
        Object result = value instanceof List<?> list ? applyList(list) : apply(value);
        document.setFieldValue(document.renderTemplate(targetField), result);
        return document;
    }

    private List<Object> applyList(List<?> list) {
        List<Object> result = new ArrayList<>();
        for (Object o : list) {
            result.add(apply(o));
        }
        return result;
    }

    private Object apply(Object value) {
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("field must be a string type");
        }
        return transform.apply(s);
    }

    public static Processor.Factory factory(String type, Function<String, String> transform) {
        return (registry, tag, description, config) -> {
            String field = ConfigurationUtils.readStringProperty(type, tag, config, "field");
            String targetField = ConfigurationUtils.readStringProperty(type, tag, config, "target_field", field);
            boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(type, tag, config, "ignore_missing", false);
            return new StringTransformProcessor(type, tag, description, field, targetField, ignoreMissing, transform);
        };
    }
}
