package com.naqqa.elasticsearch.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QueryStringParser {

    private QueryStringParser() {
    }

    public static Map<String, List<String>> parse(String rawQuery) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawName = eq >= 0 ? pair.substring(0, eq) : pair;
            String rawValue = eq >= 0 ? pair.substring(eq + 1) : "";
            String name = UrlCodec.decode(rawName, true);
            String value = UrlCodec.decode(rawValue, true);
            result.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return result;
    }

    public static List<String> splitComma(String value) {
        if (value == null || value.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }
}
