package com.naqqa.translations.dto;

import java.util.List;
import java.util.Map;

public final class TranslationsDtos {

    private TranslationsDtos() {
    }

    public record LanguageDto(String key, String label, boolean base) {
    }

    public record CatalogResponse(Map<String, Object> seed,
                                  Map<String, Object> overrides,
                                  List<LanguageDto> languages,
                                  String defaultLanguage,
                                  String referenceLanguage) {
    }

    public record OverridesResponse(boolean ok, Map<String, Object> overrides) {
    }

    public record LanguagesResponse(List<LanguageDto> languages, Map<String, Object> overrides) {
    }

    public record AddLanguageRequest(String key, String label, String copyFrom) {
    }
}
