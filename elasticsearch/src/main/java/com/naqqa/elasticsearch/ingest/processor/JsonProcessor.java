package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;
import com.naqqa.elasticsearch.ingest.json.IngestJsonParser;

import java.util.Map;

public final class JsonProcessor extends AbstractProcessor {

    public static final String TYPE = "json";

    private final String field;
    private final String targetField;
    private final boolean addToRoot;
    private final String addToRootConflictStrategy;

    public JsonProcessor(String tag, String description, String field, String targetField, boolean addToRoot, String addToRootConflictStrategy) {
        super(TYPE, tag, description);
        this.field = field;
        this.targetField = targetField;
        this.addToRoot = addToRoot;
        this.addToRootConflictStrategy = addToRootConflictStrategy;
    }

    @Override
    @SuppressWarnings("unchecked")
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        String value = document.getFieldValue(resolvedField, String.class);
        Object parsed = IngestJsonParser.parse(value);
        if (addToRoot) {
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException("[" + resolvedField + "] json content must be an object to be added to root");
            }
            Map<String, Object> parsedMap = (Map<String, Object>) parsed;
            Map<String, Object> root = document.getSourceAndMetadata();
            for (Map.Entry<String, Object> e : parsedMap.entrySet()) {
                if (root.containsKey(e.getKey())) {
                    if ("replace".equals(addToRootConflictStrategy)) {
                        root.put(e.getKey(), e.getValue());
                    } else if ("merge".equals(addToRootConflictStrategy) && root.get(e.getKey()) instanceof Map<?, ?> existing && e.getValue() instanceof Map<?, ?> incoming) {
                        ((Map<String, Object>) existing).putAll((Map<String, Object>) incoming);
                    } else {
                        throw new IllegalArgumentException("field [" + e.getKey() + "] already exists");
                    }
                } else {
                    root.put(e.getKey(), e.getValue());
                }
            }
        } else {
            document.setFieldValue(document.renderTemplate(targetField), parsed);
        }
        return document;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field", field);
            boolean addToRoot = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "add_to_root", false);
            String conflictStrategy = ConfigurationUtils.readStringProperty(TYPE, tag, config, "add_to_root_conflict_strategy", "replace");
            return new JsonProcessor(tag, description, field, targetField, addToRoot, conflictStrategy);
        }
    }
}
