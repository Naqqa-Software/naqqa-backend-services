package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class GsubProcessor extends AbstractProcessor {

    public static final String TYPE = "gsub";

    private final String field;
    private final Pattern pattern;
    private final String replacement;
    private final String targetField;
    private final boolean ignoreMissing;

    public GsubProcessor(String tag, String description, String field, Pattern pattern, String replacement, String targetField, boolean ignoreMissing) {
        super(TYPE, tag, description);
        this.field = field;
        this.pattern = pattern;
        this.replacement = replacement;
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
        return pattern.matcher(s).replaceAll(replacement);
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String patternStr = ConfigurationUtils.readStringProperty(TYPE, tag, config, "pattern");
            String replacement = ConfigurationUtils.readStringProperty(TYPE, tag, config, "replacement");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field", field);
            boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_missing", false);
            return new GsubProcessor(tag, description, field, Pattern.compile(patternStr), replacement, targetField, ignoreMissing);
        }
    }
}
