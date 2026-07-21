package com.naqqa.seofarm.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** A Pexels photo already used (dedup). Ported from used-pexels-images.json. */
@Document("seo_used_images")
@Getter
@Setter
public class SeoUsedImageEntity {

    @Id
    private String id;

    @Indexed
    private String siteId;
    private String imageId;
    private Instant usedAt;
    private String query;
    private String alt;
    private String altSignature;
    private String photographer;
}
