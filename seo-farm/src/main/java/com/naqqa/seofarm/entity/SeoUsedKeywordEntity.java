package com.naqqa.seofarm.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** A keyword already turned into a published blog (never reused). Ported from used-keywords.json. */
@Document("seo_used_keywords")
@CompoundIndex(name = "site_keyword", def = "{'siteId': 1, 'keyword': 1}", unique = true)
@Getter
@Setter
public class SeoUsedKeywordEntity {

    @Id
    private String id;

    @Indexed
    private String siteId;
    /** Normalized keyword. */
    private String keyword;
    private Instant usedAt;
}
