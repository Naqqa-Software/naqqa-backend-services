package com.naqqa.seofarm.model;

import java.util.List;

/**
 * A target site for the SEO farm (ported from the bot's per-country config). {@code fromSite} is the
 * key that ties blogs in naqqa-server to a site; {@code rootTerms} seed keyword research.
 */
public record SeoSite(
        String id,
        String name,
        boolean active,
        String domain,
        String fromSite,
        int locationCode,
        String languageCode,
        String languageName,
        String targetAudience,
        String competitivePosition,
        List<String> rootTerms) {
}
