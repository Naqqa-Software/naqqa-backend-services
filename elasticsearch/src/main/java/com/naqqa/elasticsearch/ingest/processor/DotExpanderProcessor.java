package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DotExpanderProcessor extends AbstractProcessor {

    public static final String TYPE = "dot_expander";

    private final String field;
    private final String path;

    public DotExpanderProcessor(String tag, String description, String field, String path) {
        super(TYPE, tag, description);
        this.field = field;
        this.path = path;
    }

    @Override
    @SuppressWarnings("unchecked")
    public IngestDocument execute(IngestDocument document) {
        Map<String, Object> root;
        if (path != null) {
            root = (Map<String, Object>) document.getFieldValue(path, Object.class);
        } else {
            root = document.getSourceAndMetadata();
        }
        if (root == null || !root.containsKey(field)) {
            return document;
        }
        Object value = root.remove(field);
        int dot = field.indexOf('.');
        if (dot < 0) {
            root.put(field, value);
            return document;
        }
        String first = field.substring(0, dot);
        String rest = field.substring(dot + 1);
        Object existing = root.get(first);
        Map<String, Object> nested;
        if (existing instanceof Map) {
            nested = (Map<String, Object>) existing;
        } else {
            nested = new LinkedHashMap<>();
            if (existing != null) {
                nested.put("", existing);
            }
            root.put(first, nested);
        }
        nested.put(rest, value);
        return document;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String path = ConfigurationUtils.readOptionalStringProperty(config, "path");
            return new DotExpanderProcessor(tag, description, field, path);
        }
    }
}
