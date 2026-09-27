package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.Map;

public final class SetProcessor extends AbstractProcessor {

    public static final String TYPE = "set";

    private final String field;
    private final Object value;
    private final String copyFrom;
    private final boolean override;
    private final boolean ignoreEmptyValue;

    public SetProcessor(String tag, String description, String field, Object value, String copyFrom, boolean override, boolean ignoreEmptyValue) {
        super(TYPE, tag, description);
        this.field = field;
        this.value = value;
        this.copyFrom = copyFrom;
        this.override = override;
        this.ignoreEmptyValue = ignoreEmptyValue;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        if (!override && document.hasField(resolvedField)) {
            return document;
        }
        Object resolvedValue;
        if (copyFrom != null) {
            resolvedValue = document.hasField(copyFrom) ? IngestDocument.deepCopy(document.getFieldValue(copyFrom, Object.class)) : null;
        } else if (value instanceof String s) {
            resolvedValue = document.renderTemplateValue(s);
        } else {
            resolvedValue = value;
        }
        if (ignoreEmptyValue && isEmpty(resolvedValue)) {
            return document;
        }
        document.setFieldValue(resolvedField, resolvedValue);
        return document;
    }

    private static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return s.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return m.isEmpty();
        }
        if (value instanceof java.util.List<?> l) {
            return l.isEmpty();
        }
        return false;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String copyFrom = ConfigurationUtils.readOptionalStringProperty(config, "copy_from");
            Object value = config.remove("value");
            if (copyFrom == null && value == null) {
                throw ConfigurationUtils.newConfigurationException(TYPE, tag, "value", "either [value] or [copy_from] must be specified");
            }
            boolean override = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "override", true);
            boolean ignoreEmptyValue = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_empty_value", false);
            return new SetProcessor(tag, description, field, value, copyFrom, override, ignoreEmptyValue);
        }
    }
}
