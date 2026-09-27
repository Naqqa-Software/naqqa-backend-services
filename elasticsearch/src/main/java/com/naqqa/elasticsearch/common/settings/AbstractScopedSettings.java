package com.naqqa.elasticsearch.common.settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public abstract class AbstractScopedSettings {

    private final Map<String, Setting<?>> keySettings = new HashMap<>();
    private final List<SettingUpdater<?>> settingUpdaters = new ArrayList<>();
    private volatile Settings lastSettingsApplied = Settings.EMPTY;

    protected AbstractScopedSettings(Settings settings, Set<Setting<?>> settingsSet, Setting.Property scope) {
        this.lastSettingsApplied = settings;
        for (Setting<?> setting : settingsSet) {
            keySettings.put(setting.getKey(), setting);
        }
    }

    public boolean isRegistered(Setting<?> setting) {
        return keySettings.get(setting.getKey()) == setting || keySettings.containsKey(setting.getKey());
    }

    public Setting<?> get(String key) {
        Setting<?> setting = keySettings.get(key);
        if (setting == null) {
            throw new SettingsException("unknown setting [" + key + "]");
        }
        return setting;
    }

    public void validate(String key, Settings settings) {
        Setting<?> setting = keySettings.get(key);
        if (setting == null) {
            throw new SettingsException("unknown setting [" + key + "]");
        }
        setting.get(settings);
    }

    public <T> void addSettingsUpdateConsumer(Setting<T> setting, Consumer<T> consumer) {
        if (!isRegistered(setting)) {
            throw new SettingsException("Setting [" + setting.getKey() + "] is not registered");
        }
        if (!setting.isDynamic()) {
            throw new SettingsException("Setting [" + setting.getKey() + "] is not dynamic");
        }
        settingUpdaters.add(new SettingUpdater<>(setting, consumer));
    }

    public synchronized Settings applySettings(Settings newSettings) {
        for (String key : newSettings.keySet()) {
            Setting<?> setting = keySettings.get(key);
            if (setting != null && !setting.isDynamic()) {
                throw new SettingsException("cannot update non-dynamic setting [" + key + "]");
            }
        }
        for (SettingUpdater<?> updater : settingUpdaters) {
            updater.apply(newSettings);
        }
        Settings merged = Settings.builder().put(lastSettingsApplied).put(newSettings).build();
        lastSettingsApplied = merged;
        return merged;
    }

    public Settings get() {
        return lastSettingsApplied;
    }

    private static final class SettingUpdater<T> {
        private final Setting<T> setting;
        private final Consumer<T> consumer;

        SettingUpdater(Setting<T> setting, Consumer<T> consumer) {
            this.setting = setting;
            this.consumer = consumer;
        }

        void apply(Settings newSettings) {
            if (setting.exists(newSettings)) {
                consumer.accept(setting.get(newSettings));
            }
        }
    }

    public static Set<Setting<?>> settingsSet(Setting<?>... settings) {
        return new HashSet<>(List.of(settings));
    }
}
