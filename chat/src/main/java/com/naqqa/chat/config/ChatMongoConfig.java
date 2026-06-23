package com.naqqa.chat.config;

import com.naqqa.chat.persistence.ChatSequenceListener;
import com.naqqa.chat.persistence.SequenceGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@Configuration
@EnableMongoRepositories(basePackages = "com.naqqa.chat.repository")
public class ChatMongoConfig {

    @Bean
    @ConditionalOnMissingBean(SequenceGenerator.class)
    public SequenceGenerator chatSequenceGenerator(MongoOperations mongoOperations) {
        return new SequenceGenerator(mongoOperations);
    }

    @Bean
    @ConditionalOnMissingBean(ChatSequenceListener.class)
    public ChatSequenceListener chatSequenceListener(SequenceGenerator sequenceGenerator) {
        return new ChatSequenceListener(sequenceGenerator);
    }
}
