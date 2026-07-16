package com.naqqa.outreach.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Auto-configuration for the email-outreach library: registers the Mongo repositories and the
 * {@link OutreachProperties}. The library's {@code @Service}/{@code @Component}/{@code @RestController}
 * beans (service, engine, scheduler, controller) are picked up by the host app's component scan —
 * add {@code com.naqqa.outreach} to {@code @SpringBootApplication(scanBasePackages = ...)} (same as
 * naqqa-auth). Requires the host to have {@code @EnableScheduling} + {@code @EnableAsync}
 * (naqqa-os-be already does).
 */
@Configuration
@EnableConfigurationProperties(OutreachProperties.class)
@EnableMongoRepositories(basePackages = "com.naqqa.outreach.repository")
public class OutreachAutoConfiguration {
}
