package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RemoveProcessor extends AbstractProcessor {

    public static final String TYPE = "remove";

    private final List<String> fields;
    private final List<String> keep;
    private final boolean ignoreMissing;

    public RemoveProcessor(String tag, String description, List<String> fields, List<String> keep, boolean ignoreMissing) {
        super(TYPE, tag, description);
        this.fields = fields;
        this.keep = keep;
        this.ignoreMissing = ignoreMissing;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        if (keep != null) {
            List<String> keepResolved = new ArrayList<>();
            for (String k : keep) {
                keepResolved.add(document.renderTemplate(k));
            }
            Map<String, Object> source = document.getSource();
            List<String> toRemove = new ArrayList<>();
            for (String key : source.keySet()) {
                if (!keepResolved.contains(key) && !IngestDocument.MetaData.isMetadata(key)) {
                    toRemove.add(key);
                }
            }
            for (String key : toRemove) {
                document.removeField(key);
            }
            return document;
        }
        for (String field : fields) {
            String resolved = document.renderTemplate(field);
            if (!document.hasField(resolved)) {
                if (ignoreMissing) {
                    continue;
                }
                throw new IllegalArgumentException("field [" + resolved + "] not present as part of path [" + resolved + "]");
            }
            document.removeField(resolved);
        }
        return document;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            List<String> fields = ConfigurationUtils.readOptionalStringList(config, "field");
            List<String> keep = ConfigurationUtils.readOptionalStringList(config, "keep");
            boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_missing", false);
            if (fields == null && keep == null) {
                throw ConfigurationUtils.newConfigurationException(TYPE, tag, "field", "either [field] or [keep] must be specified");
            }
            return new RemoveProcessor(tag, description, fields == null ? new ArrayList<>() : fields, keep, ignoreMissing);
        }
    }
}
