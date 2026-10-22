package com.naqqa.commons.config;

import com.naqqa.commons.web.ApiExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
