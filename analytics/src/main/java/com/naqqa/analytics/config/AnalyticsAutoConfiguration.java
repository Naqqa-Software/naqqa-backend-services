package com.naqqa.analytics.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Auto-configuration for the naqqa-analytics library: registers the Mongo repositories and
 * {@link AnalyticsProperties}. The library's {@code @Service}/{@code @Component}/{@code @RestController}
 * beans are picked up by the host app's component scan — add {@code com.naqqa.analytics} to
 * {@code @SpringBootApplication(scanBasePackages = ...)}. The rollup scheduler needs the host to have
 * {@code @EnableScheduling} (naqqa-os-be already does).
 */
@Configuration
@EnableConfigurationProperties(AnalyticsProperties.class)
@EnableMongoRepositories(basePackages = "com.naqqa.analytics.repository")
public class AnalyticsAutoConfiguration {
}
