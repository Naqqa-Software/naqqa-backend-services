package com.naqqa.elasticsearch.indices;

import com.naqqa.elasticsearch.cluster.state.Settings;

import java.util.ArrayList;
import java.util.List;

public final class IndexSortConfig {

    public enum Order {
        ASC, DESC
    }

    public enum Mode {
        MIN, MAX
    }

    public record SortField(String field, Order order, Mode mode, String missing) {
    }

    public static final IndexSortConfig EMPTY = new IndexSortConfig(List.of());

    private final List<SortField> fields;

    public IndexSortConfig(List<SortField> fields) {
        this.fields = List.copyOf(fields);
    }

    public List<SortField> getFields() {
        return fields;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    public static IndexSortConfig fromSettings(Settings settings) {
        List<String> fieldNames = splitCsv(settings.get("index.sort.field"));
        if (fieldNames.isEmpty()) {
            return EMPTY;
        }
        List<String> orders = splitCsv(settings.get("index.sort.order"));
        List<String> modes = splitCsv(settings.get("index.sort.mode"));
        List<String> missing = splitCsv(settings.get("index.sort.missing"));
        List<SortField> result = new ArrayList<>();
        for (int i = 0; i < fieldNames.size(); i++) {
            Order order = i < orders.size() ? Order.valueOf(orders.get(i).toUpperCase(java.util.Locale.ROOT)) : Order.ASC;
            Mode mode = i < modes.size() && !modes.get(i).isBlank()
                ? Mode.valueOf(modes.get(i).toUpperCase(java.util.Locale.ROOT)) : null;
            String missingValue = i < missing.size() ? missing.get(i) : null;
            result.add(new SortField(fieldNames.get(i), order, mode, missingValue));
        }
        return new IndexSortConfig(result);
    }

    private static List<String> splitCsv(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String part : value.split(",")) {
            result.add(part.trim());
        }
        return result;
    }
}
