package com.naqqa.entity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
        scanBasePackages = {
                "com.naqqa.entity",
                "com.naqqa.auth"
        }
)
public class EntityApplication {
    public static void main(String[] args) {
        SpringApplication.run(EntityApplication.class, args);
    }
}
