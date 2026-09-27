package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CsvProcessor extends AbstractProcessor {

    public static final String TYPE = "csv";

    private final String field;
    private final List<String> targetFields;
    private final char separator;
    private final char quote;
    private final boolean trim;
    private final String emptyValue;
    private final boolean ignoreMissing;

    public CsvProcessor(String tag, String description, String field, List<String> targetFields, char separator,
                         char quote, boolean trim, String emptyValue, boolean ignoreMissing) {
        super(TYPE, tag, description);
        this.field = field;
        this.targetFields = targetFields;
        this.separator = separator;
        this.quote = quote;
        this.trim = trim;
        this.emptyValue = emptyValue;
        this.ignoreMissing = ignoreMissing;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        String value = document.getFieldValue(resolvedField, String.class, ignoreMissing);
        if (value == null) {
            return document;
        }
        List<String> parts = parseLine(value);
        for (int i = 0; i < targetFields.size() && i < parts.size(); i++) {
            String v = parts.get(i);
            if (trim) {
                v = v.trim();
            }
            Object result = v.isEmpty() ? emptyValue : v;
            document.setFieldValue(document.renderTemplate(targetFields.get(i)), result);
        }
        return document;
    }

    private List<String> parseLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int len = line.length();
        while (i < len) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == quote) {
                    if (i + 1 < len && line.charAt(i + 1) == quote) {
                        current.append(quote);
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                current.append(c);
                i++;
            } else {
                if (c == quote) {
                    inQuotes = true;
                    i++;
                } else if (c == separator) {
                    result.add(current.toString());
                    current.setLength(0);
                    i++;
                } else {
                    current.append(c);
                    i++;
                }
            }
        }
        result.add(current.toString());
        return result;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            List<String> targetFields = ConfigurationUtils.readOptionalStringList(config, "target_fields");
            if (targetFields == null) {
                throw ConfigurationUtils.newConfigurationException(TYPE, tag, "target_fields", "required property is missing");
            }
            String separatorStr = ConfigurationUtils.readStringProperty(TYPE, tag, config, "separator", ",");
            String quoteStr = ConfigurationUtils.readStringProperty(TYPE, tag, config, "quote", "\"");
            boolean trim = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "trim", false);
            String emptyValue = ConfigurationUtils.readOptionalStringProperty(config, "empty_value");
            boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_missing", false);
            return new CsvProcessor(tag, description, field, targetFields, separatorStr.charAt(0), quoteStr.charAt(0), trim, emptyValue, ignoreMissing);
        }
    }
}
