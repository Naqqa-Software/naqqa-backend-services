package com.naqqa.elasticsearch.common.settings;

import com.naqqa.elasticsearch.common.unit.ByteSizeValue;
import com.naqqa.elasticsearch.common.unit.TimeValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class Settings {

    public static final Settings EMPTY = new Settings(Collections.emptyMap());

    private final Map<String, String> settings;

    private Settings(Map<String, String> settings) {
        this.settings = settings;
    }

    public String get(String key) {
        return settings.get(key);
    }

    public String get(String key, String defaultValue) {
        String v = settings.get(key);
        return v == null ? defaultValue : v;
    }

    public boolean getAsBoolean(String key, boolean defaultValue) {
        String v = settings.get(key);
        if (v == null) {
            return defaultValue;
        }
        return switch (v.toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new SettingsException("Failed to parse value [{}] as boolean for setting [{}]", v, key);
        };
    }

    public int getAsInt(String key, int defaultValue) {
        String v = settings.get(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new SettingsException("Failed to parse int setting [" + key + "] with value [" + v + "]", e);
        }
    }

    public long getAsLong(String key, long defaultValue) {
        String v = settings.get(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new SettingsException("Failed to parse long setting [" + key + "] with value [" + v + "]", e);
        }
    }

    public double getAsDouble(String key, double defaultValue) {
        String v = settings.get(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new SettingsException("Failed to parse double setting [" + key + "] with value [" + v + "]", e);
        }
    }

    public TimeValue getAsTime(String key, TimeValue defaultValue) {
        String v = settings.get(key);
        return v == null ? defaultValue : TimeValue.parseTimeValue(v, key);
    }

    public ByteSizeValue getAsBytesSize(String key, ByteSizeValue defaultValue) {
        String v = settings.get(key);
        return v == null ? defaultValue : ByteSizeValue.parseBytesSizeValue(v, key);
    }

    public List<String> getAsList(String key) {
        return getAsList(key, Collections.emptyList());
    }

    public List<String> getAsList(String key, List<String> defaultValue) {
        List<String> result = new ArrayList<>();
        String direct = settings.get(key);
        if (direct != null) {
            for (String part : direct.split(",")) {
                result.add(part.trim());
            }
            return result;
        }
        int i = 0;
        while (true) {
            String v = settings.get(key + "." + i);
            if (v == null) {
                break;
            }
            result.add(v);
            i++;
        }
        return result.isEmpty() ? defaultValue : result;
    }

    public boolean hasValue(String key) {
        return settings.containsKey(key);
    }

    public boolean isEmpty() {
        return settings.isEmpty();
    }

    public int size() {
        return settings.size();
    }

    public Set<String> keySet() {
        return settings.keySet();
    }

    public Settings getByPrefix(String prefix) {
        Builder builder = new Builder();
        for (Map.Entry<String, String> e : settings.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                builder.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        return builder.build();
    }

    public Set<String> getGroups(String settingPrefix) {
        String prefix = settingPrefix.endsWith(".") ? settingPrefix : settingPrefix + ".";
        Set<String> groups = new java.util.LinkedHashSet<>();
        for (String key : settings.keySet()) {
            if (key.startsWith(prefix)) {
                String rest = key.substring(prefix.length());
                int dot = rest.indexOf('.');
                if (dot > 0) {
                    groups.add(rest.substring(0, dot));
                }
            }
        }
        return groups;
    }

    public Map<String, String> getAsMap() {
        return Collections.unmodifiableMap(settings);
    }

    public Builder toBuilder() {
        return new Builder().put(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Settings other)) {
            return false;
        }
        return settings.equals(other.settings);
    }

    @Override
    public int hashCode() {
        return settings.hashCode();
    }

    @Override
    public String toString() {
        return new TreeMap<>(settings).toString();
    }

    public static final class Builder {
        private final Map<String, String> map = new LinkedHashMap<>();

        public Builder put(String key, String value) {
            if (value == null) {
                map.remove(key);
            } else {
                map.put(key, value);
            }
            return this;
        }

        public Builder put(String key, int value) {
            return put(key, String.valueOf(value));
        }

        public Builder put(String key, long value) {
            return put(key, String.valueOf(value));
        }

        public Builder put(String key, double value) {
            return put(key, String.valueOf(value));
        }

        public Builder put(String key, boolean value) {
            return put(key, String.valueOf(value));
        }

        public Builder put(String key, TimeValue value) {
            return put(key, value.getStringRep());
        }

        public Builder put(String key, ByteSizeValue value) {
            return put(key, value.toString());
        }

        public Builder putList(String key, List<String> values) {
            for (int i = 0; i < values.size(); i++) {
                map.put(key + "." + i, values.get(i));
            }
            return this;
        }

        public Builder put(Settings settings) {
            map.putAll(settings.settings);
            return this;
        }

        public Builder remove(String key) {
            map.remove(key);
            return this;
        }

        public Settings build() {
            return new Settings(new LinkedHashMap<>(map));
        }
    }
}
