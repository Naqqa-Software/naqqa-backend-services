package com.naqqa.seofarm.model;

import java.time.Instant;
import java.util.List;

/** Create/update payload for a blog. */
public record SaveSeoBlogRequest(
        String slug,
        String fromSite,
        String content,
        SeoBlogMeta meta,
        List<SeoBlogImage> images,
        String fullHtml,
        Instant publishedAt) {
}
