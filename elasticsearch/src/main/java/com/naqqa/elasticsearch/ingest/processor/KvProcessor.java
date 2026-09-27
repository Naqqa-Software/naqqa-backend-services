package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KvProcessor extends AbstractProcessor {

    public static final String TYPE = "kv";

    private final String field;
    private final String fieldSplit;
    private final String valueSplit;
    private final String targetField;
    private final List<String> includeKeys;
    private final List<String> excludeKeys;
    private final String prefix;
    private final String trimKey;
    private final String trimValue;
    private final boolean stripBrackets;
    private final boolean ignoreMissing;

    public KvProcessor(String tag, String description, String field, String fieldSplit, String valueSplit, String targetField,
                        List<String> includeKeys, List<String> excludeKeys, String prefix, String trimKey, String trimValue,
                        boolean stripBrackets, boolean ignoreMissing) {
        super(TYPE, tag, description);
        this.field = field;
        this.fieldSplit = fieldSplit;
        this.valueSplit = valueSplit;
        this.targetField = targetField;
        this.includeKeys = includeKeys;
        this.excludeKeys = excludeKeys;
        this.prefix = prefix;
        this.trimKey = trimKey;
        this.trimValue = trimValue;
        this.stripBrackets = stripBrackets;
        this.ignoreMissing = ignoreMissing;
    }

    @Override
    @SuppressWarnings("unchecked")
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        String value = document.getFieldValue(resolvedField, String.class, ignoreMissing);
        if (value == null) {
            return document;
        }
        Map<String, Object> target;
        if (targetField != null) {
            String resolvedTarget = document.renderTemplate(targetField);
            if (!document.hasField(resolvedTarget)) {
                document.setFieldValue(resolvedTarget, new LinkedHashMap<>());
            }
            Object existing = document.getFieldValue(resolvedTarget, Object.class);
            if (!(existing instanceof Map)) {
                throw new IllegalArgumentException("target_field [" + resolvedTarget + "] is not a map");
            }
            target = (Map<String, Object>) existing;
        } else {
            target = document.getSourceAndMetadata();
        }

        for (String pair : value.split(fieldSplit)) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf(valueSplit);
            if (idx < 0) {
                continue;
            }
            String key = pair.substring(0, idx);
            String val = pair.substring(idx + valueSplit.length());
            key = trim(key, trimKey);
            val = trim(val, trimValue);
            if (stripBrackets) {
                val = stripBrackets(val);
            }
            if (includeKeys != null && !includeKeys.contains(key)) {
                continue;
            }
            if (excludeKeys != null && excludeKeys.contains(key)) {
                continue;
            }
            String finalKey = prefix != null ? prefix + key : key;
            target.put(finalKey, val);
        }
        return document;
    }

    private static String trim(String value, String chars) {
        if (chars == null || chars.isEmpty()) {
            return value;
        }
        String result = value;
        while (!result.isEmpty() && chars.indexOf(result.charAt(0)) >= 0) {
            result = result.substring(1);
        }
        while (!result.isEmpty() && chars.indexOf(result.charAt(result.length() - 1)) >= 0) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String stripBrackets(String value) {
        if (value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '(' && last == ')') || (first == '<' && last == '>') || (first == '[' && last == ']') || (first == '"' && last == '"')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String fieldSplit = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field_split");
            String valueSplit = ConfigurationUtils.readStringProperty(TYPE, tag, config, "value_split");
            String targetField = ConfigurationUtils.readOptionalStringProperty(config, "target_field");
            List<String> includeKeys = ConfigurationUtils.readOptionalStringList(config, "include_keys");
            List<String> excludeKeys = ConfigurationUtils.readOptionalStringList(config, "exclude_keys");
            String prefix = ConfigurationUtils.readOptionalStringProperty(config, "prefix");
            String trimKey = ConfigurationUtils.readOptionalStringProperty(config, "trim_key");
            String trimValue = ConfigurationUtils.readOptionalStringProperty(config, "trim_value");
            boolean stripBrackets = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "strip_brackets", false);
            boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_missing", false);
            return new KvProcessor(tag, description, field, fieldSplit, valueSplit, targetField, includeKeys, excludeKeys,
                prefix, trimKey, trimValue, stripBrackets, ignoreMissing);
        }
    }
}
