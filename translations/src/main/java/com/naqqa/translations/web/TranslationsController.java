package com.naqqa.translations.web;

import com.naqqa.translations.dto.TranslationsDtos.AddLanguageRequest;
import com.naqqa.translations.dto.TranslationsDtos.CatalogResponse;
import com.naqqa.translations.dto.TranslationsDtos.LanguagesResponse;
import com.naqqa.translations.dto.TranslationsDtos.OverridesResponse;
import com.naqqa.translations.service.TranslationsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("${naqqa.translations.path:/api/translations}")
public class TranslationsController {

    private final TranslationsService service;

    public TranslationsController(TranslationsService service) {
        this.service = service;
    }

    @GetMapping
    public CatalogResponse catalog() {
        return service.catalog();
    }

    @PutMapping("/overrides")
    public OverridesResponse putOverrides(@RequestBody(required = false) Map<String, Object> overrides) {
        return service.saveOverrides(overrides);
    }

    @PostMapping("/overrides")
    public OverridesResponse postOverrides(@RequestBody(required = false) Map<String, Object> overrides) {
        return service.saveOverrides(overrides);
    }

    @PostMapping("/languages")
    public LanguagesResponse addLanguage(@RequestBody(required = false) AddLanguageRequest request) {
        return service.addLanguage(request);
    }
}
