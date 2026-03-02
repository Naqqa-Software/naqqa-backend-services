package com.naqqa.entity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(
        scanBasePackages = {
                "com.naqqa.deedakt",
                "com.naqqa.auth",
                "com.naqqa.filestorage"  // include the library
        }
)
@EntityScan({
        "com.naqqa.deedakt.entities",
        "com.naqqa.auth.entity",
        "com.naqqa.filestorage.entities" // include library entities
})
@EnableJpaRepositories({
        "com.naqqa.deedakt.repository",
        "com.naqqa.auth.repository",
        "com.naqqa.filestorage.repository" // include library repos
})
public class EntityApplication {
    public static void main(String[] args) {
        SpringApplication.run(EntityApplication.class, args);
    }
}
