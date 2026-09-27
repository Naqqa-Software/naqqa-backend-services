package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.Map;

public final class AppendProcessor extends AbstractProcessor {

    public static final String TYPE = "append";

    private final String field;
    private final Object value;
    private final boolean allowDuplicates;

    public AppendProcessor(String tag, String description, String field, Object value, boolean allowDuplicates) {
        super(TYPE, tag, description);
        this.field = field;
        this.value = value;
        this.allowDuplicates = allowDuplicates;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        Object resolvedValue = value instanceof String s ? document.renderTemplateValue(s) : value;
        document.appendFieldValue(resolvedField, resolvedValue, allowDuplicates);
        return document;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            Object value = config.remove("value");
            if (value == null) {
                throw ConfigurationUtils.newConfigurationException(TYPE, tag, "value", "required property is missing");
            }
            boolean allowDuplicates = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "allow_duplicates", true);
            return new AppendProcessor(tag, description, field, value, allowDuplicates);
        }
    }
}
