package com.naqqa.seofarm.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Auto-configuration for the naqqa-seo-farm library: registers the Mongo repositories and
 * {@link SeoFarmProperties}. The library's {@code @Service}/{@code @Component} beans (generation
 * pipeline + daily scheduler) are picked up by the host's component scan — add {@code com.naqqa.seofarm}
 * to {@code @SpringBootApplication(scanBasePackages = ...)}. The scheduler needs the host's
 * {@code @EnableScheduling}. The host exposes the admin/public HTTP controllers that call these services.
 *
 * <p>Claude is called via the host's {@code anthropic.api.*} properties (resolved from the host env);
 * SerpAPI + Pexels keys and all tuning come from {@code naqqa.seofarm.*}.
 */
@Configuration
@EnableConfigurationProperties(SeoFarmProperties.class)
@EnableMongoRepositories(basePackages = "com.naqqa.seofarm.repository")
public class SeoFarmAutoConfiguration {
}
