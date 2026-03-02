package com.naqqa.entity.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationPropertiesScan(basePackages = "com.naqqa.entity.config")
public class PropertiesConfig {
}

