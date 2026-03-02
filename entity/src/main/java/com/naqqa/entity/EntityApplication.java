package com.naqqa.entity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(
        scanBasePackages = {
                "com.naqqa.entity",
                "com.naqqa.auth"
        }
)
@EntityScan({
        "com.naqqa.entity.entity",
        "com.naqqa.auth.entity"
})
@EnableJpaRepositories({
        "com.naqqa.entity.repository",
        "com.naqqa.auth.repository"
})
public class EntityApplication {
    public static void main(String[] args) {
        SpringApplication.run(EntityApplication.class, args);
    }
}
