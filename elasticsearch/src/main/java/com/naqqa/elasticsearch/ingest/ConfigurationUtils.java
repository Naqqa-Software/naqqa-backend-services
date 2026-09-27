package com.naqqa.elasticsearch.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigurationUtils {

    private ConfigurationUtils() {
    }

    public static ConfigurationException newConfigurationException(String type, String tag, String key, String reason) {
        return new ConfigurationException("[" + type + (tag != null ? "/" + tag : "") + "] property [" + key + "]: " + reason);
    }

    @SuppressWarnings("unchecked")
    public static <T> T remove(String type, String tag, Map<String, Object> config, String key, T defaultValue) {
        if (!config.containsKey(key)) {
            return defaultValue;
        }
        Object value = config.remove(key);
        if (value == null) {
            return null;
        }
        return (T) value;
    }

    public static String readStringProperty(String type, String tag, Map<String, Object> config, String key) {
        String value = readOptionalStringProperty(config, key);
        if (value == null) {
            throw newConfigurationException(type, tag, key, "required property is missing");
        }
        return value;
    }

    public static String readStringProperty(String type, String tag, Map<String, Object> config, String key, String defaultValue) {
        Object value = config.remove(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    public static String readOptionalStringProperty(Map<String, Object> config, String key) {
        Object value = config.remove(key);
        return value == null ? null : String.valueOf(value);
    }

    public static boolean readBooleanProperty(String type, String tag, Map<String, Object> config, String key, boolean defaultValue) {
        Object value = config.remove(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public static int readIntProperty(String type, String tag, Map<String, Object> config, String key, int defaultValue) {
        Object value = config.remove(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    public static List<String> readOptionalStringList(Map<String, Object> config, String key) {
        Object value = config.remove(key);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                result.add(String.valueOf(o));
            }
            return result;
        }
        List<String> single = new ArrayList<>();
        single.add(String.valueOf(value));
        return single;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> readList(String type, String tag, Map<String, Object> config, String key) {
        Object value = config.remove(key);
        if (value == null) {
            return List.of();
        }
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readOptionalMap(Map<String, Object> config, String key) {
        Object value = config.remove(key);
        if (value == null) {
            return null;
        }
        return (Map<String, Object>) value;
    }

    public static Map<String, Object> readMap(String type, String tag, Map<String, Object> config, String key, Map<String, Object> defaultValue) {
        Map<String, Object> value = readOptionalMap(config, key);
        return value == null ? defaultValue : value;
    }

    public static void assertNoLeftovers(String type, String tag, Map<String, Object> config) {
        if (!config.isEmpty()) {
            throw new ConfigurationException("[" + type + (tag != null ? "/" + tag : "") + "] unsupported properties: " + new LinkedHashMap<>(config).keySet());
        }
    }
}
