package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;
import com.naqqa.elasticsearch.ingest.grok.Grok;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GrokProcessor extends AbstractProcessor {

    public static final String TYPE = "grok";

    private final String field;
    private final List<String> matchPatterns;
    private final List<Grok> groks;
    private final boolean ignoreMissing;
    private final boolean traceMatch;

    public GrokProcessor(String tag, String description, String field, List<String> matchPatterns,
                          Map<String, String> patternDefinitions, boolean ignoreMissing, boolean traceMatch) {
        super(TYPE, tag, description);
        this.field = field;
        this.matchPatterns = matchPatterns;
        this.ignoreMissing = ignoreMissing;
        this.traceMatch = traceMatch;
        this.groks = new ArrayList<>();
        for (String p : matchPatterns) {
            groks.add(new Grok(p, patternDefinitions));
        }
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        String value = document.getFieldValue(resolvedField, String.class, ignoreMissing);
        if (value == null) {
            return document;
        }
        for (int i = 0; i < groks.size(); i++) {
            Map<String, Object> captured = groks.get(i).match(value);
            if (captured != null) {
                for (Map.Entry<String, Object> e : captured.entrySet()) {
                    document.setFieldValue(e.getKey(), e.getValue());
                }
                if (traceMatch) {
                    if (matchPatterns.size() > 1) {
                        document.setFieldValue(IngestDocument.INGEST_KEY + "._grok_match_index", String.valueOf(i));
                    }
                }
                return document;
            }
        }
        throw new IllegalArgumentException("Provided Grok expressions do not match field value: [" + value + "]");
    }

    public static final class Factory implements Processor.Factory {
        @Override
        @SuppressWarnings("unchecked")
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            List<String> patterns = ConfigurationUtils.readOptionalStringList(config, "patterns");
            if (patterns == null) {
                String single = ConfigurationUtils.readOptionalStringProperty(config, "pattern");
                if (single == null) {
                    throw ConfigurationUtils.newConfigurationException(TYPE, tag, "patterns", "required property is missing");
                }
                patterns = List.of(single);
            }
            Map<String, Object> defsRaw = ConfigurationUtils.readMap(TYPE, tag, config, "pattern_definitions", Map.of());
            Map<String, String> defs = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> e : defsRaw.entrySet()) {
                defs.put(e.getKey(), String.valueOf(e.getValue()));
            }
            boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_missing", false);
            boolean traceMatch = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "trace_match", false);
            return new GrokProcessor(tag, description, field, patterns, defs, ignoreMissing, traceMatch);
        }
    }
}
