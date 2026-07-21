package com.naqqa.seofarm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.seofarm.model.SeoSite;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Competitor/SERP analysis — port of contentAnalyzer.js. Pulls the top-3 organic results for the
 * keyword, scrapes their main content (jsoup), asks Claude to distil topics/gaps/structure, and
 * returns a human-readable summary injected into the blog prompt. Fails soft (null summary).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompetitorAnalyzer {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0 Safari/537.36";

    private final SerpApiClient serp;
    private final SeoClaudeClient claude;

    public record Analysis(String summary, List<SerpApiClient.Organic> topCompetitors) {
    }

    public Analysis analyze(String keyword, SeoSite site) {
        String gl = SeoKeywordConstants.GEO_LOWER.getOrDefault(site.locationCode(), "us");
        String hl = site.languageCode() == null ? "en" : site.languageCode();

        List<SerpApiClient.Organic> results = serp.organic(keyword, gl, hl, 10);
        List<SerpApiClient.Organic> top = results.size() > 3 ? results.subList(0, 3) : results;
        if (top.isEmpty()) {
            return new Analysis(null, List.of());
        }

        StringBuilder competitorData = new StringBuilder();
        for (int i = 0; i < top.size(); i++) {
            SerpApiClient.Organic r = top.get(i);
            String content = fetchContent(r.url());
            competitorData.append("--- Result #").append(i + 1).append(" (position ").append(r.position()).append(") ---\n")
                    .append("Title: ").append(r.title()).append("\n")
                    .append("URL: ").append(r.url()).append("\n")
                    .append("Snippet: ").append(r.snippet()).append("\n");
            if (content != null && !content.isBlank()) {
                competitorData.append("Content preview: ")
                        .append(content.length() > 2000 ? content.substring(0, 2000) : content).append("\n");
            }
            competitorData.append("\n");
        }

        String summary = analyzeWithClaude(keyword, competitorData.toString(), top);
        return new Analysis(summary, top);
    }

    private String fetchContent(String url) {
        try {
            Document doc = Jsoup.connect(url).userAgent(UA).timeout(15000)
                    .followRedirects(true).ignoreHttpErrors(true).get();
            doc.select("script, style, nav, footer, header, aside, .ad, .advertisement, .cookie-banner, #cookie-notice").remove();
            String text = null;
            for (String sel : List.of("article", "main", "[role=main]", ".content", ".post-content", ".entry-content")) {
                var el = doc.selectFirst(sel);
                if (el != null) {
                    text = el.text();
                    break;
                }
            }
            if (text == null) {
                text = doc.body() != null ? doc.body().text() : "";
            }
            text = text.replaceAll("\\s+", " ").trim();
            return text.length() > 8000 ? text.substring(0, 8000) : text;
        } catch (Exception e) {
            log.debug("[seofarm] competitor fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    private String analyzeWithClaude(String keyword, String competitorData, List<SerpApiClient.Organic> top) {
        if (!claude.isConfigured()) {
            return null;
        }
        String system = """
                You analyse the top-ranking competitor pages for a keyword and return structured
                insights to help write a BETTER blog post. Return ONLY JSON:
                {"commonTopics":[],"keyPoints":[],"contentStructure":"","uniqueAngles":[],
                 "missingGaps":[],"recommendedWordCount":0,"toneAndStyle":"","keyTakeaways":[]}""";
        String user = "Keyword: " + keyword + "\n\nTop competitor pages:\n" + competitorData
                + "\nReturn JSON only.";
        JsonNode a = claude.completeJson(2048, system, user);
        if (a == null) {
            return null;
        }
        return buildSummary(a, top);
    }

    private String buildSummary(JsonNode a, List<SerpApiClient.Organic> top) {
        StringBuilder s = new StringBuilder("COMPETITOR ANALYSIS INSIGHTS:\n\nTop ranking pages:\n");
        int i = 1;
        for (SerpApiClient.Organic r : top) {
            s.append(i++).append(". [#").append(r.position()).append("] ").append(r.title()).append("\n");
        }
        appendList(s, "\nCommon topics covered", a.path("commonTopics"));
        appendList(s, "Key points to include", a.path("keyPoints"));
        if (a.hasNonNull("contentStructure")) {
            s.append("\nContent structure: ").append(a.path("contentStructure").asText()).append("\n");
        }
        appendList(s, "Unique angles to consider", a.path("uniqueAngles"));
        appendList(s, "Content gaps to fill", a.path("missingGaps"));
        if (a.path("recommendedWordCount").asInt(0) > 0) {
            s.append("\nRecommended word count: ").append(a.path("recommendedWordCount").asInt()).append(" words\n");
        }
        if (a.hasNonNull("toneAndStyle")) {
            s.append("Tone & style: ").append(a.path("toneAndStyle").asText()).append("\n");
        }
        appendList(s, "Key takeaways", a.path("keyTakeaways"));
        return s.toString();
    }

    private void appendList(StringBuilder s, String label, JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return;
        }
        s.append("\n").append(label).append(":\n");
        List<String> items = new ArrayList<>();
        arr.forEach(n -> items.add(n.asText()));
        for (String it : items) {
            s.append("- ").append(it).append("\n");
        }
    }
}
