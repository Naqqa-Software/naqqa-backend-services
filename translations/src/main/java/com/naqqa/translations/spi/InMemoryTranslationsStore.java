package com.naqqa.translations.spi;

import com.naqqa.translations.dto.TranslationsDtos.LanguageDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class InMemoryTranslationsStore implements TranslationsStore {

    private final AtomicReference<Map<String, Object>> overrides = new AtomicReference<>(new LinkedHashMap<>());
    private final AtomicReference<List<LanguageDto>> added = new AtomicReference<>(new ArrayList<>());

    @Override
    public Map<String, Object> readOverrides() {
        return new LinkedHashMap<>(overrides.get());
    }

    @Override
    public void writeOverrides(Map<String, Object> value) {
        overrides.set(value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value));
    }

    @Override
    public List<LanguageDto> readAddedLanguages() {
        return new ArrayList<>(added.get());
    }

    @Override
    public void writeAddedLanguages(List<LanguageDto> languages) {
        added.set(languages == null ? new ArrayList<>() : new ArrayList<>(languages));
    }
}
