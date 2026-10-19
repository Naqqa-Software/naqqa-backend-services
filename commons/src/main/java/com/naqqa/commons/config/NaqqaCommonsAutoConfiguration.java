package com.naqqa.commons.config;

import com.naqqa.commons.web.ApiExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for naqqa-commons. On a servlet web application it registers the shared
 * {@link ApiExceptionHandler}, so every module that depends on naqqa-commons renders the uniform
 * {@link com.naqqa.commons.error.ApiError}. The handler is {@link ConditionalOnMissingBean} and can
 * be switched off with {@code naqqa.commons.exception-handler=false}. The paging, validation and
 * logging helpers are plain utilities and need no beans.
 */
@AutoConfiguration
@EnableConfigurationProperties(NaqqaCommonsProperties.class)
@ConditionalOnWebApplication
public class NaqqaCommonsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "naqqa.commons", name = "exception-handler", havingValue = "true", matchIfMissing = true)
    public ApiExceptionHandler naqqaApiExceptionHandler() {
        return new ApiExceptionHandler();
    }
}
