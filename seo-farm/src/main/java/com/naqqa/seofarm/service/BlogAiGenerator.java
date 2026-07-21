package com.naqqa.seofarm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.seofarm.model.SeoKeywordCluster;
import com.naqqa.seofarm.model.SeoSite;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Blog generation via Claude — faithful port of aiGenerator.js (the ~200-line system prompt with
 * factual-accuracy rules, the 15 SEO sections, the internal-link allowlist, external links, E-E-A-T
 * and the per-site voice profiles). Returns the parsed blog JSON
 * {title, slug, metaDescription, tags[], body(markdown), imageKeywords, faqQuestions[],
 * internalLinks[], externalLinks[], tableOfContents[]}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BlogAiGenerator {

    private static final List<String> PROFILE_FILES =
            List.of("voice", "opinions", "stats", "stories", "humour");

    private final SeoClaudeClient claude;
    private final SeoPagesService pages;

    public JsonNode generate(SeoSite site, SeoKeywordCluster cluster, String competitorSummary,
                             List<String> existingSlugs) {
        if (!claude.isConfigured()) {
            throw new IllegalStateException("Anthropic is not configured (set ANTHROPIC_API_KEY).");
        }
        String system = buildSystemPrompt(site, competitorSummary, existingSlugs);
        String main = cluster.getKeyword();
        List<String> subs = cluster.getSubKeywords() == null ? List.of() : cluster.getSubKeywords();
        StringBuilder user = new StringBuilder("Write a blog post about this topic:\n\nMain keyword: ")
                .append(main).append("\n\nRelated sub-keywords (use naturally where they fit):\n");
        for (String s : subs) {
            user.append("  - ").append(s).append("\n");
        }
        user.append("\nNotes: none");
        return claude.completeJson(8000, system, user.toString());
    }

    private String buildSystemPrompt(SeoSite site, String competitorSummary, List<String> existingSlugs) {
        JsonNode services = (JsonNode) pages.pages().get("services");
        JsonNode siteLinks = (JsonNode) pages.pages().get("siteLinks");

        StringBuilder p = new StringBuilder();
        p.append("You are a professional tech blog writer for Naqqa — a software development and IT outsourcing company based in Eastern Europe (Moldova, IT Park resident).\n\n");
        p.append("**Company: Naqqa**\n")
                .append("**Target Site:** ").append(site.name()).append(" (").append(site.domain()).append(")\n")
                .append("**Target Audience:** ").append(site.targetAudience()).append("\n")
                .append("**Language:** ").append(site.languageName()).append("\n")
                .append("**Positioning:** ").append(site.competitivePosition()).append("\n\n");

        p.append("**NAQQA SERVICES — reference these accurately when relevant:**\n");
        for (JsonNode s : services) {
            p.append("- **").append(s.path("title").asText()).append("** (").append(s.path("url").asText())
                    .append("): ").append(s.path("description").asText()).append("\n  Features: ");
            List<String> feats = new java.util.ArrayList<>();
            s.path("features").forEach(f -> feats.add(f.asText()));
            p.append(String.join(", ", feats)).append("\n");
        }

        p.append("""

                **CURRENT DATE: June 2026**

                **CRITICAL: FACTUAL ACCURACY IS MANDATORY.**
                - ONLY cite statistics you can verify, or use generic phrasing ("industry reports show demand outpacing supply").
                - AVOID specific invented numbers/percentages and NEVER fabricate sources (no "Gartner 2025"/"Deloitte 2025" unless real). Use "according to industry research", "studies indicate".
                - Be accurate about companies. Moldova IT Park facts: 7% single tax on sales revenue (NOT "0% income tax"), over 2,700 resident companies, turnover exceeded $1 billion in 2025.
                - Use cautious language ("often", "typically", "can", "many"). Mention Naqqa minimally (1-2 times). PRICE CLAIMS: add "*Indicative market ranges — vary by seniority, contract model, and provider.*" and use ranges not exact numbers.

                **ARTICLE UNIQUENESS:** distinct angle, not a generic overview (e.g. "What UK buyers get wrong", "Red flags to avoid", "Hidden costs"). Use comparison tables and "Best for" boxes.

                **SEO BEST PRACTICES — IMPLEMENT ALL 15 SECTIONS:**
                1. URL: slug under 60 chars, primary keyword, hyphens only, no stop words.
                2. HEADINGS: exactly ONE H1 (template-set) — NEVER add H1 in body; logical H2→H3, H2s use supporting keywords.
                3. QUICK ANSWER BOX (REQUIRED, right after the intro): a blockquote "> **Quick answer:** [2-3 sentences]".
                4. CONTENT DEPTH 1800-2500 words: start with intro then Quick Answer then content; short paragraphs; 8th-10th grade; active voice; NO title as H1; NO "Table of Contents" section.
                5. VISUAL STRUCTURE (>=2 of): markdown comparison table; "> **Best for:** …" boxes; "> **⚠️ Red flag:** …" boxes; mid-article "> **💡 …**" CTA; summary bullets after each H2.
                6. FAQ SECTION (REQUIRED, 4-8 Q): from People Also Ask; each answer 2-4 sentences; H3 per question; add "## FAQs" H2.
                7. INTERNAL LINKS (3-5) — STRICT ALLOWLIST ONLY (below). Use [anchor text] in the body; set exact "url" in internalLinks.
                8. EXTERNAL LINKS (2-3, REQUIRED): authoritative sources (.gov/.edu/Wikipedia/official docs); write full markdown links [anchor](https://…); never fabricate URLs; also list in externalLinks.
                9. PRICE CLAIMS — soft wording (mandatory), ranges only.
                10. E-E-A-T: "In our experience…", "We've observed…"; no fabricated case studies.

                **INTERNAL-LINK ALLOWLIST — static site pages:**
                """);
        for (JsonNode link : siteLinks.path("internalPages")) {
            p.append("  - ").append(link.path("url").asText()).append("  → \"").append(link.path("label").asText()).append("\"\n");
        }
        p.append("**Published blog posts on this site:**\n");
        if (existingSlugs == null || existingSlugs.isEmpty()) {
            p.append("  (none yet — do not invent blog slugs)\n");
        } else {
            for (String slug : existingSlugs) {
                p.append("  - /blog/").append(slug).append("\n");
            }
        }
        p.append("Rules: use [anchor text] in body (system converts to links); NEVER link a blog slug not listed above.\n\n");

        p.append("Apply the following personality and style guide precisely:\n").append(loadPersonality(site.id()));

        p.append("\n\nThe main keyword MUST appear in the title and naturally 2-3 times in the body. Weave sub-keywords into headings and body naturally.\n");
        if (competitorSummary != null && !competitorSummary.isBlank()) {
            p.append("\nIMPORTANT: use these competitor-analysis insights — cover the common topics, include the key points, use a similar structure with unique angles, fill the gaps:\n")
                    .append(competitorSummary).append("\n");
        }

        p.append("""

                Return ONLY valid JSON (no markdown fences, no extra text) with this exact structure:
                {
                  "title": string,            // max 60 chars, primary keyword
                  "slug": string,             // lowercase, hyphens only, under 60 chars
                  "metaDescription": string,  // 150-160 chars, keyword + CTA
                  "tags": string[],           // 3-5 tags
                  "body": string,             // Markdown, 1500-2000 words; NO title H1; NO Table of Contents; start with intro; use [anchor text] links; include "## FAQs"
                  "imageKeywords": string,    // 2-3 word phrase for the hero image
                  "faqQuestions": [ { "question": string, "answer": string } ],
                  "internalLinks": [ { "anchor": string, "url": string, "context": string } ],
                  "externalLinks": [ { "url": string, "anchor": string, "context": string } ],
                  "tableOfContents": [ { "heading": string, "anchor": string } ]
                }
                Return ONLY the raw JSON object.""");
        return p.toString();
    }

    /** Concatenate the 5 voice-profile markdown files for a site (bundled resources). */
    private String loadPersonality(String siteId) {
        StringBuilder sb = new StringBuilder();
        for (String name : PROFILE_FILES) {
            try {
                var res = new ClassPathResource("seofarm/profiles/" + siteId + "/" + name + ".md");
                if (res.exists()) {
                    String content = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                    sb.append("\n\n--- ").append(name.toUpperCase()).append(" ---\n").append(content);
                }
            } catch (Exception e) {
                log.debug("[seofarm] profile {}/{} missing: {}", siteId, name, e.getMessage());
            }
        }
        return sb.toString();
    }
}
