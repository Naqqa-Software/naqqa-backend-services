package com.naqqa.auth.security;

import com.naqqa.auth.sso.SsoProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

@Configuration
public class StepUpWebConfig implements WebMvcConfigurer {

    private final SsoProperties properties;
    private final Clock clock;
    private final ObjectProvider<StepUpFactorPolicy> factorPolicy;

    public StepUpWebConfig(SsoProperties properties, Clock clock, ObjectProvider<StepUpFactorPolicy> factorPolicy) {
        this.properties = properties;
        this.clock = clock;
        this.factorPolicy = factorPolicy;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new StepUpInterceptor(properties, clock, factorPolicy::getIfAvailable));
    }
}
