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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DissectProcessor extends AbstractProcessor {

    public static final String TYPE = "dissect";
    private static final Pattern KEY_TOKEN = Pattern.compile("%\\{([+*&?]?)([A-Za-z0-9_.\\[\\]]*)(?:/(\\d+))?(->)?}");

    private record KeyToken(String modifier, String name, Integer ordinal, boolean skipPadding) {
    }

    private final String field;
    private final String pattern;
    private final String appendSeparator;
    private final boolean ignoreMissing;
    private final List<String> delims;
    private final List<KeyToken> keys;

    public DissectProcessor(String tag, String description, String field, String pattern, String appendSeparator, boolean ignoreMissing) {
        super(TYPE, tag, description);
        this.field = field;
        this.pattern = pattern;
        this.appendSeparator = appendSeparator;
        this.ignoreMissing = ignoreMissing;
        this.delims = new ArrayList<>();
        this.keys = new ArrayList<>();
        compile(pattern);
    }

    private void compile(String pattern) {
        Matcher m = KEY_TOKEN.matcher(pattern);
        int last = 0;
        while (m.find()) {
            delims.add(pattern.substring(last, m.start()));
            Integer ordinal = m.group(3) != null ? Integer.parseInt(m.group(3)) : null;
            keys.add(new KeyToken(m.group(1), m.group(2), ordinal, m.group(4) != null));
            last = m.end();
        }
        delims.add(pattern.substring(last));
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        String value = document.getFieldValue(resolvedField, String.class, ignoreMissing);
        if (value == null) {
            return document;
        }
        Map<String, Object> extracted = dissect(value);
        for (Map.Entry<String, Object> e : extracted.entrySet()) {
            document.setFieldValue(e.getKey(), e.getValue());
        }
        return document;
    }

    private Map<String, Object> dissect(String input) {
        int pos = 0;
        if (!input.startsWith(delims.get(0), 0)) {
            throw new IllegalArgumentException("Unable to find match for dissect pattern: " + pattern);
        }
        pos += delims.get(0).length();
        Map<String, String> starRefs = new LinkedHashMap<>();
        Map<String, List<String>> appends = new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();

        for (int i = 0; i < keys.size(); i++) {
            KeyToken key = keys.get(i);
            String nextDelim = delims.get(i + 1);
            String value;
            if (nextDelim.isEmpty()) {
                value = input.substring(pos);
                pos = input.length();
            } else {
                int idx = input.indexOf(nextDelim, pos);
                if (idx < 0) {
                    throw new IllegalArgumentException("Unable to find match for dissect pattern: " + pattern);
                }
                value = input.substring(pos, idx);
                pos = idx + nextDelim.length();
                if (key.skipPadding()) {
                    while (input.startsWith(nextDelim, pos)) {
                        pos += nextDelim.length();
                    }
                }
            }
            store(key, value, result, starRefs, appends);
        }

        for (Map.Entry<String, List<String>> e : appends.entrySet()) {
            result.put(e.getKey(), String.join(appendSeparator, e.getValue()));
        }
        return result;
    }

    private void store(KeyToken key, String value, Map<String, Object> result, Map<String, String> starRefs, Map<String, List<String>> appends) {
        switch (key.modifier()) {
            case "?":
                break;
            case "*":
                starRefs.put(key.name(), value);
                break;
            case "&": {
                String keyName = starRefs.getOrDefault(key.name(), key.name());
                result.put(keyName, value);
                break;
            }
            case "+":
                appends.computeIfAbsent(key.name(), k -> new ArrayList<>()).add(value);
                break;
            default:
                if (!key.name().isEmpty()) {
                    result.put(key.name(), value);
                }
        }
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String pattern = ConfigurationUtils.readStringProperty(TYPE, tag, config, "pattern");
            String appendSeparator = ConfigurationUtils.readStringProperty(TYPE, tag, config, "append_separator", "");
            boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_missing", false);
            return new DissectProcessor(tag, description, field, pattern, appendSeparator, ignoreMissing);
        }
    }
}
