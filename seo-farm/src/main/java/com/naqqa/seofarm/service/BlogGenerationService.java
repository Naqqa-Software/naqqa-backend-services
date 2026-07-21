package com.naqqa.seofarm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.seofarm.entity.SeoBlogEntity;
import com.naqqa.seofarm.entity.SeoGenerationLogEntity;
import com.naqqa.seofarm.model.SeoBlogImage;
import com.naqqa.seofarm.model.SeoBlogMeta;
import com.naqqa.seofarm.model.SeoKeywordCluster;
import com.naqqa.seofarm.model.SeoSite;
import com.naqqa.seofarm.repository.SeoGenerationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end blog generation — port of blogGenerator.js orchestration: (extract keywords if due) →
 * next topic → competitor analysis → existing slugs → Claude blog → Pexels image → HTML build →
 * save to Mongo → log → mark keyword used. Each attempt is recorded in {@code seo_generation_logs}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BlogGenerationService {

    private final KeywordResearchService keywords;
    private final CompetitorAnalyzer competitor;
    private final BlogAiGenerator ai;
    private final PexelsService pexels;
    private final BlogHtmlBuilder html;
    private final SeoBlogService blogs;
    private final SeoGenerationLogRepository logs;

    public record Result(String status, String slug, String title, String error, String blogId) {
    }

    /** Hard safety cap on how many clusters we walk past (cheap DB checks) in a single run. */
    private static final int MAX_SCAN = 60;
    /** How many actual AI generations we're willing to spend per run before giving up. */
    private static final int MAX_AI_ATTEMPTS = 4;

    public synchronized Result generateForSite(SeoSite site, String trigger) {
        try {
            if (keywords.isExtractionDue(site.id())) {
                log.info("[seofarm] {} keyword extraction due — extracting.", site.id());
                keywords.extractAndStore(site);
            }
            boolean reExtracted = false;
            int aiAttempts = 0;
            String lastSkip = "no keywords available";

            for (int scan = 0; scan < MAX_SCAN; scan++) {
                SeoKeywordCluster cluster = keywords.nextCluster(site.id());
                if (cluster == null && !reExtracted) {
                    // Ran out of stored keywords — force ONE fresh extraction, then keep going.
                    log.info("[seofarm] {} keyword pool exhausted — re-extracting.", site.id());
                    keywords.extractAndStore(site);
                    reExtracted = true;
                    cluster = keywords.nextCluster(site.id());
                }
                if (cluster == null) {
                    return record(site, null, null, "SKIPPED", lastSkip, trigger, null);
                }

                String mainKeyword = cluster.getKeyword();

                // Cheap pre-check (DB only, no AI): if the keyword itself already maps to an existing
                // blog, mark it used and walk to the next topic — does NOT consume the AI budget.
                if (blogs.existsBySlug(slugify("", mainKeyword))) {
                    keywords.markUsed(site.id(), mainKeyword);
                    lastSkip = "all candidate topics already covered";
                    log.info("[seofarm] {} topic '{}' already covered — trying next.", site.id(), mainKeyword);
                    continue;
                }

                // Novel candidate — spend an AI generation (bounded).
                if (aiAttempts >= MAX_AI_ATTEMPTS) {
                    log.info("[seofarm] {} AI-attempt budget ({}) spent — stopping this run.", site.id(), MAX_AI_ATTEMPTS);
                    return record(site, null, null, "SKIPPED", lastSkip, trigger, null);
                }
                aiAttempts++;

                List<String> existingSlugs = blogs.slugsByFromSite(site.fromSite());
                CompetitorAnalyzer.Analysis analysis = competitor.analyze(mainKeyword, site);
                JsonNode blog = ai.generate(site, cluster, analysis.summary(), existingSlugs);
                if (blog == null) {
                    keywords.markUsed(site.id(), mainKeyword);
                    lastSkip = "AI returned no/invalid content";
                    log.warn("[seofarm] {} AI produced nothing for '{}' — trying next.", site.id(), mainKeyword);
                    continue;
                }

                String title = blog.path("title").asText("");
                String slug = slugify(blog.path("slug").asText(""), title);
                // Unique by topic: if this slug already exists (generated OR imported), don't create a
                // near-duplicate "-2" — mark the keyword used and walk to the next topic.
                if (slug.isBlank() || blogs.existsBySlug(slug)) {
                    keywords.markUsed(site.id(), mainKeyword);
                    lastSkip = "duplicate topic — a blog with slug '" + slug + "' already exists";
                    log.info("[seofarm] {} generated slug '{}' already exists — trying next.", site.id(), slug);
                    continue;
                }

                List<String> subs = cluster.getSubKeywords() == null ? List.of() : cluster.getSubKeywords();
                SeoBlogImage image = pexels.getImageForBlog(site.id(),
                        blog.path("imageKeywords").asText(title), mainKeyword, subs);
                if (image == null) {
                    log.warn("[seofarm] {} publishing '{}' WITHOUT an image (Pexels returned none).", site.id(), slug);
                }

                BlogHtmlBuilder.Result rendered = html.build(blog, image, site.fromSite(), slug);

                SeoBlogEntity entity = new SeoBlogEntity();
                entity.setSlug(slug);
                entity.setFromSite(site.fromSite());
                entity.setContent(rendered.bodyHtml());
                entity.setFullHtml(rendered.fullHtml());
                entity.setImages(image != null ? List.of(image) : List.of());
                entity.setMeta(SeoBlogMeta.builder()
                        .title(title)
                        .description(blog.path("metaDescription").asText(""))
                        .keywords(collectKeywords(mainKeyword, subs))
                        .tags(toList(blog.path("tags")))
                        .language(site.languageCode())
                        .country(site.id() == null ? null : site.id().toUpperCase())
                        .build());
                entity.setPublishedAt(Instant.now());
                entity.setCreatedAt(Instant.now());
                entity.setUpdatedAt(Instant.now());
                SeoBlogEntity saved = blogs.save(entity);

                keywords.markUsed(site.id(), mainKeyword);
                log.info("[seofarm] {} published blog '{}' ({}).", site.id(), title, slug);
                return record(site, mainKeyword, title, "PUBLISHED", null, trigger, saved);
            }

            // Ran out of attempts without a fresh topic.
            return record(site, null, null, "SKIPPED", lastSkip, trigger, null);
        } catch (Exception e) {
            log.warn("[seofarm] {} generation failed: {}", site.id(), e.getMessage());
            return record(site, null, null, "FAILED", e.getMessage(), trigger, null);
        }
    }

    private Result record(SeoSite site, String keyword, String title, String status, String error,
                          String trigger, SeoBlogEntity saved) {
        SeoGenerationLogEntity l = new SeoGenerationLogEntity();
        l.setSiteId(site.id());
        l.setKeyword(keyword);
        l.setTitle(title);
        l.setSlug(saved != null ? saved.getSlug() : null);
        l.setStatus(status);
        l.setError(error);
        l.setTrigger(trigger);
        l.setCreatedAt(Instant.now());
        logs.save(l);
        return new Result(status, saved != null ? saved.getSlug() : null, title, error,
                saved != null ? saved.getId() : null);
    }

    /** Deterministic slug from the AI slug/title — no uniqueness suffix (duplicates are skipped upstream). */
    private String slugify(String slug, String title) {
        String base = (slug == null || slug.isBlank() ? title : slug).toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "").trim().replaceAll("\\s+", "-").replaceAll("-+", "-");
        if (base.length() > 70) {
            base = base.substring(0, 70).replaceAll("-+$", "");
        }
        return base;
    }

    private List<String> collectKeywords(String main, List<String> subs) {
        List<String> out = new ArrayList<>();
        out.add(main);
        out.addAll(subs);
        return out;
    }

    private List<String> toList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> out.add(n.asText()));
        }
        return out;
    }
}
