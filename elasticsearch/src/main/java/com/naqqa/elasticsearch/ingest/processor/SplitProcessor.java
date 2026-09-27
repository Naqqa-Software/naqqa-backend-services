package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SplitProcessor extends AbstractProcessor {

    public static final String TYPE = "split";

    private final String field;
    private final String separator;
    private final String targetField;
    private final boolean ignoreMissing;
    private final boolean preserveTrailing;

    public SplitProcessor(String tag, String description, String field, String separator, String targetField, boolean ignoreMissing, boolean preserveTrailing) {
        super(TYPE, tag, description);
        this.field = field;
        this.separator = separator;
        this.targetField = targetField;
        this.ignoreMissing = ignoreMissing;
        this.preserveTrailing = preserveTrailing;
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
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("field [" + resolvedField + "] of type [" + value.getClass().getName() + "] cannot be split");
        }
        String[] parts = s.split(separator, preserveTrailing ? -1 : 0);
        List<Object> result = new ArrayList<>(List.of((Object[]) parts));
        document.setFieldValue(document.renderTemplate(targetField), result);
        return document;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String separator = ConfigurationUtils.readStringProperty(TYPE, tag, config, "separator");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field", field);
            boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_missing", false);
            boolean preserveTrailing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "preserve_trailing", false);
            return new SplitProcessor(tag, description, field, separator, targetField, ignoreMissing, preserveTrailing);
        }
    }
}
