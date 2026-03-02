package com.naqqa.entity.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@Configuration
@EnableMongoRepositories(basePackages = "com.naqqa.entity.repository.mongo")
public class MongoConfig {
}
