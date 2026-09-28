package com.naqqa.elasticsearch.node.search;

import com.naqqa.elasticsearch.codec.NumericUtils;
import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedNumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedSetDocValuesReader;
import com.naqqa.elasticsearch.common.regex.Regex;
import com.naqqa.elasticsearch.common.time.DateFormatter;
import com.naqqa.elasticsearch.search.execution.LeafReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FieldValues {

    private static final Set<String> INTEGRAL = Set.of("long", "integer", "short", "byte", "unsigned_long", "token_count");
    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);

    enum Kind { LONG, DOUBLE, DATE, BOOLEAN, STRING, NONE }

    private FieldValues() {
    }

    static Kind kind(QueryFactory.FieldType type) {
        if (type == null) {
            return Kind.NONE;
        }
        String t = type.type();
        if (INTEGRAL.contains(t)) {
            return Kind.LONG;
        }
        return switch (t) {
            case "double", "float", "half_float", "scaled_float" -> Kind.DOUBLE;
            case "date", "date_nanos" -> Kind.DATE;
            case "boolean" -> Kind.BOOLEAN;
            case "text", "match_only_text", "object", "nested", "dense_vector", "sparse_vector", "completion", "geo_point",
                 "geo_shape", "binary", "search_as_you_type", "annotated_text" -> Kind.NONE;
            default -> Kind.STRING;
        };
    }

    static boolean isNumeric(Kind kind) {
        return kind == Kind.LONG || kind == Kind.DOUBLE || kind == Kind.DATE || kind == Kind.BOOLEAN;
    }

    static List<Object> read(LeafReader reader, String field, QueryFactory.FieldType type, int doc) throws IOException {
        Kind kind = kind(type);
        List<Object> out = new ArrayList<>();
        if (kind == Kind.NONE) {
            return out;
        }
        if (isNumeric(kind)) {
            NumericDocValuesReader single = reader.numericDocValues(field);
            if (single != null && single.advanceExact(doc)) {
                out.add(decode(type, kind, single.longValue()));
                return out;
            }
            SortedNumericDocValuesReader multi = reader.sortedNumericDocValues(field);
            if (multi != null && multi.advanceExact(doc)) {
                int count = multi.docValueCount();
                for (int i = 0; i < count; i++) {
                    out.add(decode(type, kind, multi.nextValue()));
                }
            }
            return out;
        }
        SortedSetDocValuesReader set = reader.sortedSetDocValues(field);
        if (set != null && set.advanceExact(doc)) {
            int count = set.docValueCount();
            for (int i = 0; i < count; i++) {
                out.add(new String(set.lookupOrd((int) set.nextOrd()), StandardCharsets.UTF_8));
            }
            return out;
        }
        SortedDocValuesReader sorted = reader.sortedDocValues(field);
        if (sorted != null && sorted.advanceExact(doc)) {
            out.add(new String(sorted.lookupOrd(sorted.ordValue()), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static Object decode(QueryFactory.FieldType type, Kind kind, long raw) {
        String t = type.type();
        return switch (kind) {
            case LONG, DATE -> raw;
            case BOOLEAN -> raw != 0;
            case DOUBLE -> switch (t) {
                case "float", "half_float" -> (double) NumericUtils.sortableIntToFloat((int) raw);
                case "scaled_float" -> raw / (type.scalingFactor() <= 0 ? 1.0 : type.scalingFactor());
                default -> NumericUtils.sortableLongToDouble(raw);
            };
            default -> raw;
        };
    }

    static Object format(QueryFactory.FieldType type, Object value, String format) {
        if (value == null || type == null) {
            return value;
        }
        if (kind(type) == Kind.DATE && value instanceof Long millis) {
            boolean nanos = "date_nanos".equals(type.type());
            String f = format != null ? format : null;
            if ("epoch_millis".equals(f)) {
                return String.valueOf(nanos ? millis / 1_000_000L : millis);
            }
            if ("epoch_second".equals(f)) {
                return String.valueOf((nanos ? millis / 1_000_000L : millis) / 1000L);
            }
            Instant instant = nanos ? Instant.ofEpochSecond(millis / 1_000_000_000L, millis % 1_000_000_000L) : Instant.ofEpochMilli(millis);
            if (f != null && !"strict_date_optional_time".equals(f) && !"date_optional_time".equals(f)) {
                try {
                    return DateFormatter.forPattern(f).withZone(ZoneOffset.UTC).format(instant);
                } catch (RuntimeException ignored) {
                }
            }
            return ISO_MILLIS.format(instant);
        }
        return value;
    }

    static List<Object> fromSource(Map<String, Object> source, String path) {
        List<Object> out = new ArrayList<>();
        if (source != null) {
            collect(source, path.split("\\."), 0, out);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object node, String[] parts, int index, List<Object> out) {
        if (node == null) {
            return;
        }
        if (index == parts.length) {
            if (node instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null) {
                        out.add(o);
                    }
                }
            } else {
                out.add(node);
            }
            return;
        }
        if (node instanceof List<?> list) {
            for (Object o : list) {
                collect(o, parts, index, out);
            }
            return;
        }
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        Map<String, Object> m = (Map<String, Object>) map;
        StringBuilder key = new StringBuilder();
        for (int i = index; i < parts.length; i++) {
            if (i > index) {
                key.append('.');
            }
            key.append(parts[i]);
            if (m.containsKey(key.toString())) {
                collect(m.get(key.toString()), parts, i + 1, out);
            }
        }
    }

    static void flattenPaths(String prefix, Map<String, Object> source, Map<String, List<Object>> out) {
        for (Map.Entry<String, Object> e : source.entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            flattenValue(path, e.getValue(), out);
        }
    }

    @SuppressWarnings("unchecked")
    private static void flattenValue(String path, Object value, Map<String, List<Object>> out) {
        if (value instanceof Map<?, ?> m) {
            flattenPaths(path, (Map<String, Object>) m, out);
        } else if (value instanceof List<?> list) {
            for (Object o : list) {
                flattenValue(path, o, out);
            }
        } else if (value != null) {
            out.computeIfAbsent(path, k -> new ArrayList<>()).add(value);
        }
    }

    static Map<String, Object> filterSource(Map<String, Object> source, List<String> includes, List<String> excludes) {
        if (source == null) {
            return null;
        }
        if ((includes == null || includes.isEmpty()) && (excludes == null || excludes.isEmpty())) {
            return source;
        }
        return filterMap(source, "", includes == null ? List.of() : includes, excludes == null ? List.of() : excludes, includes == null || includes.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> filterMap(Map<String, Object> map, String prefix, List<String> includes, List<String> excludes,
                                                 boolean included) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (matchesAny(excludes, path)) {
                continue;
            }
            boolean selfIncluded = included || matchesAny(includes, path);
            Object value = e.getValue();
            if (value instanceof Map<?, ?> child) {
                if (selfIncluded || mayMatchDescendant(includes, path)) {
                    Map<String, Object> filtered = filterMap((Map<String, Object>) child, path, includes, excludes, selfIncluded);
                    if (selfIncluded || !filtered.isEmpty()) {
                        out.put(e.getKey(), filtered);
                    }
                }
            } else if (value instanceof List<?> list && containsMaps(list)) {
                if (selfIncluded || mayMatchDescendant(includes, path)) {
                    List<Object> filteredList = new ArrayList<>();
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> m) {
                            Map<String, Object> filtered = filterMap((Map<String, Object>) m, path, includes, excludes, selfIncluded);
                            if (selfIncluded || !filtered.isEmpty()) {
                                filteredList.add(filtered);
                            }
                        } else if (selfIncluded) {
                            filteredList.add(o);
                        }
                    }
                    if (selfIncluded || !filteredList.isEmpty()) {
                        out.put(e.getKey(), filteredList);
                    }
                }
            } else if (selfIncluded) {
                out.put(e.getKey(), value);
            }
        }
        return out;
    }

    private static boolean containsMaps(List<?> list) {
        for (Object o : list) {
            if (o instanceof Map<?, ?>) {
                return true;
            }
        }
        return false;
    }

    private static boolean mayMatchDescendant(List<String> includes, String path) {
        for (String p : includes) {
            if (p.startsWith(path + ".") || p.contains("*")) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesAny(List<String> patterns, String path) {
        for (String p : patterns) {
            if (p.equals(path) || (Regex.isSimpleMatchPattern(p) && Regex.simpleMatch(p, path))) {
                return true;
            }
            if (!Regex.isSimpleMatchPattern(p) && path.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }
}
