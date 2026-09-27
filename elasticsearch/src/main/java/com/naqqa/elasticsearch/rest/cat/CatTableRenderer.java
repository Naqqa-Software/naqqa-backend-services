package com.naqqa.elasticsearch.rest.cat;

import com.naqqa.elasticsearch.common.unit.ByteSizeUnit;
import com.naqqa.elasticsearch.common.unit.ByteSizeValue;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.http.Json;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.http.RestResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CatTableRenderer {

    private static final Set<String> BYTE_COLUMNS = Set.of("store.size", "pri.store.size", "size", "size.memory",
        "disk.indices", "disk.used", "disk.avail", "disk.total", "bytes", "bytes_recovered", "bytes_total",
        "merges.total_size", "fielddata.memory_size", "query_cache.memory_size", "request_cache.memory_size");

    private static final Set<String> TIME_COLUMNS = Set.of("time", "max_task_wait_time", "timeInQueue");

    private CatTableRenderer() {
    }

    public static RestResponse render(RestRequest request, CatActionService.CatTable table) {
        List<String> columns = resolveColumns(request, table.columns());
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, String> row : table.rows()) {
            rows.add(formatRow(row, request));
        }
        rows = sortRows(rows, request);

        if ("json".equalsIgnoreCase(request.param("format"))) {
            List<Object> jsonRows = new ArrayList<>();
            for (Map<String, String> row : rows) {
                Map<String, Object> filtered = new LinkedHashMap<>();
                for (String col : columns) {
                    filtered.put(col, row.get(col));
                }
                jsonRows.add(filtered);
            }
            return RestResponse.json(200, Json.write(jsonRows, request.pretty()));
        }

        boolean verbose = request.paramAsBoolean("v", false);
        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = verbose ? columns.get(i).length() : 0;
            for (Map<String, String> row : rows) {
                String value = row.getOrDefault(columns.get(i), "");
                widths[i] = Math.max(widths[i], value.length());
            }
        }
        StringBuilder sb = new StringBuilder();
        if (verbose) {
            appendLine(sb, columns, widths, columns);
        }
        for (Map<String, String> row : rows) {
            List<String> values = new ArrayList<>();
            for (String col : columns) {
                values.add(row.getOrDefault(col, ""));
            }
            appendLine(sb, columns, widths, values);
        }
        return RestResponse.text(200, sb.toString());
    }

    private static void appendLine(StringBuilder sb, List<String> columns, int[] widths, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            sb.append(value);
            if (i < values.size() - 1) {
                int pad = Math.max(1, widths[i] - value.length() + 1);
                for (int p = 0; p < pad; p++) {
                    sb.append(' ');
                }
            }
        }
        sb.append('\n');
    }

    private static List<String> resolveColumns(RestRequest request, List<String> defaultColumns) {
        List<String> requested = request.paramAsList("h");
        if (requested.isEmpty()) {
            return defaultColumns;
        }
        return requested;
    }

    private static Map<String, String> formatRow(Map<String, String> row, RestRequest request) {
        Map<String, String> result = new LinkedHashMap<>(row);
        String bytesParam = request.param("bytes");
        if (bytesParam != null) {
            for (String column : BYTE_COLUMNS) {
                String raw = result.get(column);
                if (raw != null && isLong(raw)) {
                    result.put(column, formatBytes(Long.parseLong(raw), bytesParam));
                }
            }
        }
        String timeParam = request.param("time");
        if (timeParam != null) {
            for (String column : TIME_COLUMNS) {
                String raw = result.get(column);
                if (raw != null && isLong(raw)) {
                    result.put(column, formatTime(Long.parseLong(raw), timeParam));
                }
            }
        }
        return result;
    }

    private static boolean isLong(String value) {
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String formatBytes(long bytes, String unit) {
        ByteSizeValue value = ByteSizeValue.ofBytes(bytes);
        switch (unit.toLowerCase(java.util.Locale.ROOT)) {
            case "b" -> {
                return String.valueOf(value.getBytes());
            }
            case "kb" -> {
                return (value.getBytes() / ByteSizeUnit.KB.toBytes(1)) + "kb";
            }
            case "mb" -> {
                return (value.getBytes() / ByteSizeUnit.MB.toBytes(1)) + "mb";
            }
            case "gb" -> {
                return (value.getBytes() / ByteSizeUnit.GB.toBytes(1)) + "gb";
            }
            default -> {
                return value.toString();
            }
        }
    }

    private static String formatTime(long millis, String unit) {
        TimeValue value = TimeValue.timeValueMillis(millis);
        switch (unit.toLowerCase(java.util.Locale.ROOT)) {
            case "s" -> {
                return value.seconds() + "s";
            }
            case "m" -> {
                return value.minutes() + "m";
            }
            case "h" -> {
                return value.hours() + "h";
            }
            case "ms" -> {
                return value.millis() + "ms";
            }
            default -> {
                return value.getStringRep();
            }
        }
    }

    private static List<Map<String, String>> sortRows(List<Map<String, String>> rows, RestRequest request) {
        List<String> sortSpecs = request.paramAsList("s");
        if (sortSpecs.isEmpty()) {
            return rows;
        }
        List<Map<String, String>> sorted = new ArrayList<>(rows);
        Comparator<Map<String, String>> comparator = null;
        for (String spec : sortSpecs) {
            boolean descending = false;
            int colon = spec.indexOf(':');
            final String column;
            if (colon >= 0) {
                column = spec.substring(0, colon);
                descending = spec.substring(colon + 1).equalsIgnoreCase("desc");
            } else {
                column = spec;
            }
            Comparator<Map<String, String>> keyComparator = Comparator.comparing(
                row -> row.getOrDefault(column, ""), CatTableRenderer::compareValues);
            if (descending) {
                keyComparator = keyComparator.reversed();
            }
            comparator = comparator == null ? keyComparator : comparator.thenComparing(keyComparator);
        }
        sorted.sort(comparator);
        return sorted;
    }

    private static int compareValues(String a, String b) {
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }
}
