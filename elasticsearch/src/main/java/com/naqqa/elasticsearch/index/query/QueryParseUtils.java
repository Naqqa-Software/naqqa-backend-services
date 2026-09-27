package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.common.automaton.StringDistance;
import com.naqqa.elasticsearch.common.exception.ParsingException;
import com.naqqa.elasticsearch.common.unit.Fuzziness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class QueryParseUtils {

    private QueryParseUtils() {
    }

    public static ParsingException error(String msg, Object... args) {
        return new ParsingException(-1, -1, msg, args);
    }

    public static String didYouMean(String field, Collection<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = StringDistance.levenshtein(field, candidate);
            if (distance < bestDistance && distance <= Math.max(1, field.length() / 2)) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    public static ParsingException unknownField(String queryName, String field, Set<String> known) {
        String suggestion = didYouMean(field, known);
        if (suggestion != null) {
            return error("[{}] query does not support [{}], did you mean [{}]?", queryName, field, suggestion);
        }
        return error("[{}] query does not support [{}]", queryName, field);
    }

    public static ParsingException unknownQueryType(String name, Set<String> known) {
        String suggestion = didYouMean(name, known);
        if (suggestion != null) {
            return error("no [query] registered for [{}], did you mean [{}]?", name, suggestion);
        }
        return error("no [query] registered for [{}]", name);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value, String context) {
        if (!(value instanceof Map)) {
            throw error("[{}] query malformed, expected an object but got [{}]", context, value);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
            result.put(String.valueOf(e.getKey()), e.getValue());
        }
        return result;
    }

    public static List<Object> asList(Object value, String context) {
        if (value instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        List<Object> single = new ArrayList<>();
        single.add(value);
        return single;
    }

    public static List<String> asStringList(Object value) {
        List<String> out = new ArrayList<>();
        for (Object o : asList(value, "list")) {
            out.add(asString(o));
        }
        return out;
    }

    public static String asString(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    public static float asFloat(Object value) {
        if (value instanceof Number n) {
            return n.floatValue();
        }
        return Float.parseFloat(value.toString());
    }

    public static double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    public static int asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    public static boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(value.toString());
    }

    public static Fuzziness asFuzziness(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return Fuzziness.fromEdits(n.intValue());
        }
        return Fuzziness.fromString(value.toString());
    }

    public static Map.Entry<String, Object> singleField(String queryName, Map<String, Object> value) {
        if (value.size() != 1) {
            List<String> keys = new ArrayList<>(value.keySet());
            if (keys.size() > 1) {
                throw error("[{}] query doesn't support multiple fields, found [{}] and [{}]", queryName, keys.get(0), keys.get(1));
            }
            throw error("[{}] query requires a field", queryName);
        }
        return value.entrySet().iterator().next();
    }
}
