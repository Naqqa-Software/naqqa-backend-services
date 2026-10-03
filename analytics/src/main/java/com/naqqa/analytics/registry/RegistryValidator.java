package com.naqqa.analytics.registry;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class RegistryValidator {

    public static final String UNKNOWN_EVENT = "unknown_event";
    public static final String SERVER_ONLY = "server_only";
    public static final String TOO_MANY_PROPS = "too_many_props";

    private final EventRegistry registry;
    private final int maxProps;
    private final int defaultMaxString;
    private final int maxUrl;

    public RegistryValidator(EventRegistry registry, int maxProps, int defaultMaxString, int maxUrl) {
        this.registry = registry;
        this.maxProps = maxProps;
        this.defaultMaxString = defaultMaxString;
        this.maxUrl = maxUrl;
    }

    public EventRegistry registry() {
        return registry;
    }

    public Result validate(String name, Map<String, Object> raw, boolean server) {
        EventSpec spec = registry.event(name);
        if (spec == null) {
            return Result.reject(UNKNOWN_EVENT);
        }
        if (spec.serverOnly() && !server) {
            return Result.reject(SERVER_ONLY);
        }
        Map<String, Object> common = new LinkedHashMap<>();
        Map<String, Object> props = new LinkedHashMap<>();
        int dropped = 0;
        if (raw != null) {
            if (raw.size() > maxProps * 2) {
                return Result.reject(TOO_MANY_PROPS);
            }
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                String key = e.getKey();
                PropSpec c = registry.common().get(key);
                PropSpec p = spec.props().get(key);
                if (c != null) {
                    Object v = coerce(c, e.getValue());
                    if (v != null) {
                        common.put(key, v);
                    } else if (e.getValue() != null) {
                        dropped++;
                    }
                } else if (p != null) {
                    if (props.size() >= maxProps) {
                        dropped++;
                        continue;
                    }
                    Object v = coerce(p, e.getValue());
                    if (v != null) {
                        props.put(key, v);
                    } else if (e.getValue() != null) {
                        dropped++;
                    }
                } else {
                    dropped++;
                }
            }
        }
        if (common.containsKey("entityType") != common.containsKey("entityId")) {
            common.remove("entityType");
            common.remove("entityId");
            dropped++;
        }
        return new Result(true, null, name, common, props, dropped);
    }

    public Object coerce(PropSpec spec, Object value) {
        if (value == null) {
            return null;
        }
        int max = spec.max() == null ? defaultMaxString : spec.max();
        switch (spec.type()) {
            case "string" -> {
                if (value instanceof Map || value instanceof Iterable) {
                    return null;
                }
                String s = PiiScrubber.truncate(PiiScrubber.scrub(String.valueOf(value).trim()), max);
                return s == null || s.isEmpty() ? null : s;
            }
            case "query" -> {
                return value instanceof String s ? PiiScrubber.normalizeQuery(s, max) : null;
            }
            case "url" -> {
                return value instanceof String s ? PiiScrubber.sanitizeUrl(s, Math.min(max, maxUrl)) : null;
            }
            case "path" -> {
                return value instanceof String s ? PiiScrubber.sanitizePath(s, max) : null;
            }
            case "enum" -> {
                if (value instanceof Map || value instanceof Iterable) {
                    return null;
                }
                String s = String.valueOf(value).trim();
                if (spec.values().contains(s)) {
                    return s;
                }
                String up = s.toUpperCase(Locale.ROOT);
                if (spec.values().contains(up)) {
                    return up;
                }
                String low = s.toLowerCase(Locale.ROOT);
                return spec.values().contains(low) ? low : null;
            }
            case "bool" -> {
                if (value instanceof Boolean b) {
                    return b;
                }
                String s = String.valueOf(value);
                if ("true".equals(s) || "1".equals(s)) {
                    return true;
                }
                if ("false".equals(s) || "0".equals(s)) {
                    return false;
                }
                return null;
            }
            case "int" -> {
                Double d = number(value);
                if (d == null || d.isNaN() || d.isInfinite()) {
                    return null;
                }
                long l = Math.round(d);
                if (spec.min() != null && l < spec.min() || spec.maxValue() != null && l > spec.maxValue()) {
                    return null;
                }
                if (l <= Integer.MAX_VALUE && l >= Integer.MIN_VALUE) {
                    return (int) l;
                }
                return l;
            }
            case "number" -> {
                Double d = number(value);
                if (d == null || d.isNaN() || d.isInfinite()) {
                    return null;
                }
                if (spec.min() != null && d < spec.min() || spec.maxValue() != null && d > spec.maxValue()) {
                    return null;
                }
                return d;
            }
            case "object" -> {
                if (!(value instanceof Map<?, ?> m)) {
                    return null;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (out.size() >= max) {
                        break;
                    }
                    Object v = e.getValue();
                    String k = PiiScrubber.truncate(String.valueOf(e.getKey()), 40);
                    if (v instanceof Number || v instanceof Boolean) {
                        out.put(k, v);
                    } else if (v instanceof String s) {
                        out.put(k, PiiScrubber.truncate(PiiScrubber.scrub(s), 60));
                    }
                }
                return out.isEmpty() ? null : out;
            }
            default -> {
                return null;
            }
        }
    }

    private static Double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public record Result(boolean ok, String reason, String name, Map<String, Object> common, Map<String, Object> props,
                         int droppedProps) {

        static Result reject(String reason) {
            return new Result(false, reason, null, Map.of(), Map.of(), 0);
        }
    }
}
