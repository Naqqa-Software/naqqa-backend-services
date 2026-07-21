package com.naqqa.seofarm.entity;

import com.naqqa.seofarm.model.SeoKeywordCluster;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/** One weekly keyword-extraction batch for a site (ported from keywords.json batches). */
@Document("seo_keyword_batches")
@Getter
@Setter
public class SeoKeywordBatchEntity {

    @Id
    private String id;

    @Indexed
    private String siteId;

    private Instant extractedAt;
    /** "serpapi" | "fallback". */
    private String source;
    private int count;
    private List<SeoKeywordCluster> clusters;
}
