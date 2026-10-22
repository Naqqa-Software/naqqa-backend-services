package com.naqqa.translations.spi;

import com.naqqa.translations.dto.TranslationsDtos.LanguageDto;

import java.util.List;
import java.util.Map;

public interface TranslationsStore {

    Map<String, Object> readOverrides();

    void writeOverrides(Map<String, Object> overrides);

    List<LanguageDto> readAddedLanguages();

    void writeAddedLanguages(List<LanguageDto> languages);
}
