package com.naqqa.elasticsearch.indices.template;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class IndexTemplateV2 {

    private final String name;
    private final List<String> indexPatterns;
    private final List<String> composedOf;
    private final long priority;
    private final Template template;
    private final boolean dataStreamTemplate;
    private final String timestampField;
    private final Map<String, Object> meta;

    public IndexTemplateV2(String name, List<String> indexPatterns, List<String> composedOf, long priority,
                            Template template, boolean dataStreamTemplate, String timestampField,
                            Map<String, Object> meta) {
        this.name = name;
        this.indexPatterns = List.copyOf(indexPatterns);
        this.composedOf = composedOf == null ? List.of() : List.copyOf(composedOf);
        this.priority = priority;
        this.template = template == null ? Template.EMPTY : template;
        this.dataStreamTemplate = dataStreamTemplate;
        this.timestampField = timestampField == null ? "@timestamp" : timestampField;
        this.meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public String getName() {
        return name;
    }

    public List<String> getIndexPatterns() {
        return indexPatterns;
    }

    public List<String> getComposedOf() {
        return composedOf;
    }

    public long getPriority() {
        return priority;
    }

    public Template getTemplate() {
        return template;
    }

    public boolean isDataStreamTemplate() {
        return dataStreamTemplate;
    }

    public String getTimestampField() {
        return timestampField;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    public boolean matches(String indexName) {
        for (String pattern : indexPatterns) {
            if (matchesGlob(pattern, indexName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesGlob(String pattern, String value) {
        String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q");
        return value.matches(regex);
    }
}
