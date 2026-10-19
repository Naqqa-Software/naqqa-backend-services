package com.naqqa.observability.config;

import com.naqqa.observability.service.ObsBreadthService;
import com.naqqa.observability.service.ObservabilityService;
import com.naqqa.observability.web.ObservabilityController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ObservabilityProperties.class)
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "naqqa.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObservabilityService naqqaObservabilityService() {
        return new ObservabilityService();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObsBreadthService naqqaObsBreadthService() {
        return new ObsBreadthService();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObservabilityController naqqaObservabilityController(ObservabilityService core, ObsBreadthService breadth) {
        return new ObservabilityController(core, breadth);
    }
}
