package com.naqqa.scheduler.config;

import com.naqqa.scheduler.persistence.SchedulerEventSequenceListener;
import com.naqqa.scheduler.persistence.SequenceGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@Configuration
@EnableMongoRepositories(basePackages = "com.naqqa.scheduler.repository")
public class SchedulerMongoConfig {

    @Bean
    @ConditionalOnMissingBean
    public SequenceGenerator schedulerSequenceGenerator(MongoOperations mongoOperations) {
        return new SequenceGenerator(mongoOperations);
    }

    @Bean
    @ConditionalOnMissingBean
    public SchedulerEventSequenceListener schedulerEventSequenceListener(SequenceGenerator sequenceGenerator) {
        return new SchedulerEventSequenceListener(sequenceGenerator);
    }
}
