package com.naqqa.translations.config;

import com.naqqa.translations.service.TranslationsService;
import com.naqqa.translations.spi.InMemoryTranslationsStore;
import com.naqqa.translations.spi.TranslationsSeedProvider;
import com.naqqa.translations.spi.TranslationsStore;
import com.naqqa.translations.web.TranslationsController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;
import java.util.Map;

@AutoConfiguration
@EnableConfigurationProperties(TranslationsProperties.class)
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "naqqa.translations", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TranslationsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TranslationsStore naqqaTranslationsStore() {
        return new InMemoryTranslationsStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public TranslationsSeedProvider naqqaTranslationsSeedProvider() {
        return LinkedHashMap::new;
    }

    @Bean
    @ConditionalOnMissingBean
    public TranslationsService naqqaTranslationsService(TranslationsProperties properties, TranslationsStore store,
                                                        TranslationsSeedProvider seedProvider) {
        return new TranslationsService(properties, store, seedProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public TranslationsController naqqaTranslationsController(TranslationsService service) {
        return new TranslationsController(service);
    }
}
