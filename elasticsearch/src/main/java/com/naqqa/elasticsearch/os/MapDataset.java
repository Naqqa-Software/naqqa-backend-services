package com.naqqa.elasticsearch.os;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class MapDataset implements SearchDataset {

    private final String index;
    private final Supplier<List<Map<String, Object>>> docs;
    private final String idField;
    private final List<String> textFields;

    public MapDataset(String index, Supplier<List<Map<String, Object>>> docs, String idField, List<String> textFields) {
        this.index = index;
        this.docs = docs;
        this.idField = idField;
        this.textFields = textFields;
    }

    @Override
    public String index() {
        return index;
    }

    @Override
    public List<Map<String, Object>> docs() {
        return docs.get();
    }

    @Override
    public String id(Map<String, Object> doc) {
        return String.valueOf(SearchExecutor.defaultField(doc, idField));
    }

    @Override
    public Object text(Map<String, Object> doc) {
        StringBuilder sb = new StringBuilder();
        if (textFields == null || textFields.isEmpty()) {
            for (Object value : doc.values()) {
                append(sb, value);
            }
        } else {
            for (String field : textFields) {
                append(sb, SearchExecutor.defaultField(doc, field));
            }
        }
        return sb.toString().trim();
    }

    private static void append(StringBuilder sb, Object value) {
        if (value == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(value);
    }
}
