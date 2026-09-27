package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ForeachProcessor extends AbstractProcessor {

    public static final String TYPE = "foreach";
    private static final String VALUE_KEY = "_value";

    private final String field;
    private final Processor processor;
    private final boolean ignoreMissing;

    public ForeachProcessor(String tag, String description, String field, Processor processor, boolean ignoreMissing) {
        super(TYPE, tag, description);
        this.field = field;
        this.processor = processor;
        this.ignoreMissing = ignoreMissing;
    }

    @Override
    public IngestDocument execute(IngestDocument document) throws Exception {
        String resolvedField = document.renderTemplate(field);
        Object value = document.hasField(resolvedField) ? document.getFieldValue(resolvedField, Object.class) : null;
        if (value == null) {
            if (ignoreMissing) {
                return document;
            }
            throw new IllegalArgumentException("field [" + resolvedField + "] not present as part of path [" + resolvedField + "]");
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("field [" + resolvedField + "] is not a list");
        }
        List<Object> mutable = new ArrayList<>(list);
        for (int i = 0; i < mutable.size(); i++) {
            document.getIngestMetadata().put(VALUE_KEY, mutable.get(i));
            IngestDocument result = processor.execute(document);
            if (result == null) {
                document.getIngestMetadata().remove(VALUE_KEY);
                return null;
            }
            mutable.set(i, document.getIngestMetadata().remove(VALUE_KEY));
        }
        document.setFieldValue(resolvedField, mutable);
        return document;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        @SuppressWarnings("unchecked")
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            Map<String, Object> processorConfig = ConfigurationUtils.readOptionalMap(config, "processor");
            if (processorConfig == null) {
                throw ConfigurationUtils.newConfigurationException(TYPE, tag, "processor", "required property is missing");
            }
            boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_missing", false);
            Processor inner = registry.buildProcessor(processorConfig);
            return new ForeachProcessor(tag, description, field, inner, ignoreMissing);
        }
    }
}
