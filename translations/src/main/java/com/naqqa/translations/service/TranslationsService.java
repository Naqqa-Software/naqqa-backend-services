package com.naqqa.translations.service;

import com.naqqa.translations.config.TranslationsProperties;
import com.naqqa.translations.dto.TranslationsDtos.AddLanguageRequest;
import com.naqqa.translations.dto.TranslationsDtos.CatalogResponse;
import com.naqqa.translations.dto.TranslationsDtos.LanguageDto;
import com.naqqa.translations.dto.TranslationsDtos.LanguagesResponse;
import com.naqqa.translations.dto.TranslationsDtos.OverridesResponse;
import com.naqqa.translations.spi.TranslationsSeedProvider;
import com.naqqa.translations.spi.TranslationsStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TranslationsService {

    private final TranslationsProperties properties;
    private final TranslationsStore store;
    private final TranslationsSeedProvider seedProvider;

    public TranslationsService(TranslationsProperties properties, TranslationsStore store,
                               TranslationsSeedProvider seedProvider) {
        this.properties = properties;
        this.store = store;
        this.seedProvider = seedProvider;
    }

    public CatalogResponse catalog() {
        Map<String, Object> seed = seed();
        return new CatalogResponse(seed, store.readOverrides(), languageList(seed),
                properties.getDefaultLanguage(), referenceLanguage(seed));
    }

    public OverridesResponse saveOverrides(Map<String, Object> overrides) {
        Map<String, Object> value = overrides == null ? new LinkedHashMap<>() : overrides;
        store.writeOverrides(value);
        return new OverridesResponse(true, value);
    }

    public LanguagesResponse addLanguage(AddLanguageRequest request) {
        Map<String, Object> seed = seed();
        if (request == null || request.key() == null || request.key().isBlank()) {
            return new LanguagesResponse(languageList(seed), store.readOverrides());
        }
        String key = request.key().trim().toLowerCase();
        String label = request.label() == null || request.label().isBlank() ? key : request.label().trim();
        List<LanguageDto> added = store.readAddedLanguages();
        boolean isBase = baseLanguageKeys(seed).contains(key);
        boolean present = added.stream().anyMatch(l -> l.key().equals(key));
        if (!isBase && !present) {
            List<LanguageDto> next = new ArrayList<>(added);
            next.add(new LanguageDto(key, label, false));
            store.writeAddedLanguages(next);
        }
        Map<String, Object> overrides = store.readOverrides();
        if (request.copyFrom() != null && !request.copyFrom().isBlank()) {
            Map<String, Object> merged = deepMerge(seed, overrides);
            Object source = merged.get(request.copyFrom());
            if (source instanceof Map<?, ?> sourceMap) {
                overrides = new LinkedHashMap<>(overrides);
                overrides.put(key, clone(castTree(sourceMap)));
                store.writeOverrides(overrides);
            }
        }
        return new LanguagesResponse(languageList(seed), overrides);
    }

    private Map<String, Object> seed() {
        Map<String, Object> seed = seedProvider == null ? null : seedProvider.seed();
        return seed == null ? new LinkedHashMap<>() : seed;
    }

    private List<String> baseLanguageKeys(Map<String, Object> seed) {
        if (seed.isEmpty()) {
            return properties.getBaseLanguages();
        }
        return new ArrayList<>(seed.keySet());
    }

    private String referenceLanguage(Map<String, Object> seed) {
        List<String> keys = baseLanguageKeys(seed);
        if (keys.contains("en")) {
            return "en";
        }
        if (keys.contains(properties.getDefaultLanguage())) {
            return properties.getDefaultLanguage();
        }
        return keys.isEmpty() ? properties.getDefaultLanguage() : keys.get(0);
    }

    private List<LanguageDto> languageList(Map<String, Object> seed) {
        List<LanguageDto> out = new ArrayList<>();
        for (String key : baseLanguageKeys(seed)) {
            String label = properties.getLanguageLabels().getOrDefault(key, key.toUpperCase());
            out.add(new LanguageDto(key, label, true));
        }
        for (LanguageDto added : store.readAddedLanguages()) {
            boolean seen = out.stream().anyMatch(l -> l.key().equals(added.key()));
            if (!seen) {
                out.add(new LanguageDto(added.key(), added.label(), false));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castTree(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            Object incoming = entry.getValue();
            Object current = result.get(entry.getKey());
            if (incoming instanceof Map && current instanceof Map) {
                result.put(entry.getKey(), deepMerge((Map<String, Object>) current, (Map<String, Object>) incoming));
            } else {
                result.put(entry.getKey(), incoming);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object clone(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), clone(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(clone(item));
            }
            return out;
        }
        return value;
    }
}
