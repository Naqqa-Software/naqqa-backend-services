package com.naqqa.translations.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "naqqa.translations")
public class TranslationsProperties {

    private boolean enabled = true;
    private String path = "/api/translations";
    private String defaultLanguage = "ro";
    private List<String> baseLanguages = List.of("ro", "ru", "en");
    private Map<String, String> languageLabels = defaultLabels();

    private static Map<String, String> defaultLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("ro", "Română");
        labels.put("ru", "Русский");
        labels.put("en", "English");
        return labels;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    public void setDefaultLanguage(String defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
    }

    public List<String> getBaseLanguages() {
        return baseLanguages;
    }

    public void setBaseLanguages(List<String> baseLanguages) {
        this.baseLanguages = baseLanguages;
    }

    public Map<String, String> getLanguageLabels() {
        return languageLabels;
    }

    public void setLanguageLabels(Map<String, String> languageLabels) {
        this.languageLabels = languageLabels;
    }
}
