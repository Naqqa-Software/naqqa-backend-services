package com.naqqa.seofarm.entity;

import com.naqqa.seofarm.model.SeoBlogImage;
import com.naqqa.seofarm.model.SeoBlogMeta;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * A generated/published SEO blog — now OWNED by this app's MongoDB (migrated out of naqqa-server).
 * The published websites read these via the public endpoints; {@code fromSite} ties a blog to a site.
 */
@Document("seo_blogs")
@Getter
@Setter
public class SeoBlogEntity {

    @Id
    private String id;

    @Indexed(unique = true)
    private String slug;

    @Indexed
    private String fromSite;

    private String content;
    private SeoBlogMeta meta;
    private List<SeoBlogImage> images;
    private String fullHtml;

    private Instant publishedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
