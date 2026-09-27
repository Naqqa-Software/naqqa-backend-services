package com.naqqa.elasticsearch.common.settings;

import com.naqqa.elasticsearch.common.unit.ByteSizeValue;
import com.naqqa.elasticsearch.common.unit.TimeValue;

import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public final class Setting<T> {

    public enum Property {
        DYNAMIC,
        FINAL,
        NODE_SCOPE,
        INDEX_SCOPE,
        CLUSTER_SCOPE,
        FILTERED,
        DEPRECATED
    }

    private final String key;
    private final Function<Settings, String> defaultValueFn;
    private final Function<String, T> parser;
    private final Consumer<T> validator;
    private final EnumSet<Property> properties;

    public Setting(String key, Function<Settings, String> defaultValueFn, Function<String, T> parser, Consumer<T> validator, Property... properties) {
        this.key = key;
        this.defaultValueFn = defaultValueFn;
        this.parser = parser;
        this.validator = validator;
        this.properties = properties.length == 0 ? EnumSet.noneOf(Property.class) : EnumSet.copyOf(List.of(properties));
        if (this.properties.contains(Property.FINAL) && this.properties.contains(Property.DYNAMIC)) {
            throw new IllegalArgumentException("Setting [" + key + "] cannot be both dynamic and final");
        }
    }

    public String getKey() {
        return key;
    }

    public boolean isDynamic() {
        return properties.contains(Property.DYNAMIC);
    }

    public boolean isFinal() {
        return properties.contains(Property.FINAL);
    }

    public boolean hasNodeScope() {
        return properties.contains(Property.NODE_SCOPE);
    }

    public boolean hasIndexScope() {
        return properties.contains(Property.INDEX_SCOPE);
    }

    public boolean hasClusterScope() {
        return properties.contains(Property.CLUSTER_SCOPE);
    }

    public boolean isFiltered() {
        return properties.contains(Property.FILTERED);
    }

    public boolean isDeprecated() {
        return properties.contains(Property.DEPRECATED);
    }

    public EnumSet<Property> getProperties() {
        return properties;
    }

    public String getDefaultRaw(Settings settings) {
        return defaultValueFn.apply(settings);
    }

    public T getDefault(Settings settings) {
        return parse(getDefaultRaw(settings));
    }

    public T parse(String value) {
        T parsed = parser.apply(value);
        if (validator != null) {
            validator.accept(parsed);
        }
        return parsed;
    }

    public T get(Settings settings) {
        String raw = settings.get(key);
        if (raw == null) {
            return getDefault(settings);
        }
        return parse(raw);
    }

    public boolean exists(Settings settings) {
        return settings.hasValue(key);
    }

    public static Setting<Boolean> boolSetting(String key, boolean defaultValue, Property... properties) {
        return new Setting<>(key, s -> String.valueOf(defaultValue), Boolean::parseBoolean, null, properties);
    }

    public static Setting<Integer> intSetting(String key, int defaultValue, Property... properties) {
        return intSetting(key, defaultValue, Integer.MIN_VALUE, Integer.MAX_VALUE, properties);
    }

    public static Setting<Integer> intSetting(String key, int defaultValue, int min, int max, Property... properties) {
        return new Setting<>(key, s -> String.valueOf(defaultValue), s -> {
            int v = Integer.parseInt(s.trim());
            if (v < min || v > max) {
                throw new IllegalArgumentException("Failed to parse value [" + s + "] for setting [" + key + "] must be between " + min + " and " + max);
            }
            return v;
        }, null, properties);
    }

    public static Setting<Long> longSetting(String key, long defaultValue, Property... properties) {
        return new Setting<>(key, s -> String.valueOf(defaultValue), s -> Long.parseLong(s.trim()), null, properties);
    }

    public static Setting<Double> doubleSetting(String key, double defaultValue, Property... properties) {
        return new Setting<>(key, s -> String.valueOf(defaultValue), s -> Double.parseDouble(s.trim()), null, properties);
    }

    public static Setting<String> simpleString(String key, String defaultValue, Property... properties) {
        return new Setting<>(key, s -> defaultValue, Function.identity(), null, properties);
    }

    public static Setting<String> simpleString(String key, Function<Settings, String> defaultValueFn, Property... properties) {
        return new Setting<>(key, defaultValueFn, Function.identity(), null, properties);
    }

    public static Setting<TimeValue> timeSetting(String key, TimeValue defaultValue, Property... properties) {
        return new Setting<>(key, s -> defaultValue.getStringRep(), s -> TimeValue.parseTimeValue(s, key), null, properties);
    }

    public static Setting<ByteSizeValue> byteSizeSetting(String key, ByteSizeValue defaultValue, Property... properties) {
        return new Setting<>(key, s -> defaultValue.toString(), s -> ByteSizeValue.parseBytesSizeValue(s, key), null, properties);
    }

    public static Setting<List<String>> listSetting(String key, List<String> defaultValue, Property... properties) {
        return new Setting<>(key, s -> String.join(",", defaultValue), s -> {
            if (s.isEmpty()) {
                return List.of();
            }
            return List.of(s.split(","));
        }, null, properties);
    }

    public static <T> Setting<T> withValidator(Setting<T> setting, Consumer<T> validator) {
        return new Setting<>(setting.key, setting.defaultValueFn, setting.parser, validator, setting.properties.toArray(new Property[0]));
    }
}
