package com.naqqa.elasticsearch.search.suggest.completion;

import com.naqqa.elasticsearch.common.geo.Geohash;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ContextQuery {

    private final Map<String, List<String>> categoryFilters = new HashMap<>();
    private final Map<String, String> geoFilters = new HashMap<>();

    public ContextQuery category(String contextName, String... values) {
        categoryFilters.computeIfAbsent(contextName, k -> new ArrayList<>()).addAll(List.of(values));
        return this;
    }

    public ContextQuery geo(String contextName, double lon, double lat, int precision) {
        geoFilters.put(contextName, Geohash.stringEncode(lon, lat, precision));
        return this;
    }

    public boolean matches(CompletionEntry entry) {
        for (Map.Entry<String, List<String>> filter : categoryFilters.entrySet()) {
            List<String> values = entry.contexts().get(filter.getKey());
            if (values == null || values.stream().noneMatch(filter.getValue()::contains)) {
                return false;
            }
        }
        for (Map.Entry<String, String> filter : geoFilters.entrySet()) {
            List<String> values = entry.contexts().get(filter.getKey());
            if (values == null || values.stream().noneMatch(v -> matchesGeoCell(v, filter.getValue()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesGeoCell(String entryCell, String queryCell) {
        int len = Math.min(entryCell.length(), queryCell.length());
        return entryCell.substring(0, len).equals(queryCell.substring(0, len));
    }
}
