package com.naqqa.seofarm.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** One blog-generation attempt (success or failure) — powers the statistics tab + history. */
@Document("seo_generation_logs")
@Getter
@Setter
public class SeoGenerationLogEntity {

    @Id
    private String id;

    @Indexed
    private String siteId;
    private String keyword;
    private String slug;
    private String title;
    /** GENERATED | PUBLISHED | FAILED | SKIPPED. */
    private String status;
    private String error;
    private String trigger; // "schedule" | "manual"
    private Instant createdAt;
}
