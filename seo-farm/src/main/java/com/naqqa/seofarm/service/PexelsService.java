package com.naqqa.seofarm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.seofarm.config.SeoFarmProperties;
import com.naqqa.seofarm.entity.SeoUsedImageEntity;
import com.naqqa.seofarm.model.SeoBlogImage;
import com.naqqa.seofarm.repository.SeoUsedImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.*;

/**
 * Pexels image search + selection — port of pexelsService.js. Generates search queries (Claude +
 * fallback), searches Pexels, scores candidates (dedup by used-id / alt-signature / photographer,
 * aspect ratio, keyword-in-alt, dimensions, off-topic penalties) and returns the best unused image
 * for a site, recording it so it isn't reused. Fails soft (null).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PexelsService {

    private static final List<String> BAD_TERMS = List.of("food", "restaurant", "wedding", "movie",
            "fashion", "animal", "nature", "flower", "beach", "sport", "party", "children", "baby");
    private static final List<String> GOOD_TERMS = List.of("software", "developer", "code", "computer",
            "office", "team", "laptop", "programming", "technology", "engineer", "business", "meeting");
    private static final List<String> BAD_QUERY_WORDS = List.of("uk", "us", "best", "top", "company",
            "companies", "services", "pricing", "cost", "hire", "guide", "cheap", "rate");

    private final SeoFarmProperties props;
    private final SeoUsedImageRepository usedRepo;
    private final SeoClaudeClient claude;
    private final RestClient http = RestClient.create();

    public boolean isConfigured() {
        return props.getPexelsApiKey() != null && !props.getPexelsApiKey().isBlank();
    }

    public SeoBlogImage getImageForBlog(String siteId, String title, String mainKeyword, List<String> subKeywords) {
        if (!isConfigured()) {
            return null;
        }
        List<String> queries = buildQueries(title, mainKeyword, subKeywords);
        Set<String> usedIds = new HashSet<>();
        Set<String> usedAltSigs = new HashSet<>();
        for (SeoUsedImageEntity u : usedRepo.findBySiteId(siteId)) {
            usedIds.add(u.getImageId());
            if (u.getAltSignature() != null) usedAltSigs.add(u.getAltSignature());
        }

        List<Candidate> candidates = new ArrayList<>();
        for (String q : queries) {
            int page = 1 + new Random().nextInt(3);
            for (JsonNode photo : search(q, page)) {
                candidates.add(score(photo, q, mainKeyword, subKeywords, usedIds, usedAltSigs));
            }
        }
        candidates.removeIf(c -> c == null || c.score <= -9000);

        // Fallback pass: if the topical queries yielded nothing usable (transient failure, narrow
        // topic, or every candidate was already used), retry the safe generics on page 1 so we don't
        // publish an image-less blog.
        if (candidates.isEmpty()) {
            log.warn("[seofarm] Pexels: no usable image from topical queries {} for '{}' — trying generic fallback.",
                    queries, mainKeyword);
            for (String q : List.of("software development team", "developers working office",
                    "business technology meeting", "programming computer")) {
                for (JsonNode photo : search(q, 1)) {
                    candidates.add(score(photo, q, mainKeyword, subKeywords, usedIds, usedAltSigs));
                }
            }
            candidates.removeIf(c -> c == null || c.score <= -9000);
        }
        if (candidates.isEmpty()) {
            log.warn("[seofarm] Pexels found NO usable image for '{}' (site {}) — blog will have no image.",
                    mainKeyword, siteId);
            return null;
        }
        candidates.sort(Comparator.comparingInt((Candidate c) -> c.score).reversed());
        List<Candidate> pool = candidates.subList(0, Math.min(8, candidates.size()));
        Candidate chosen = pool.get(new Random().nextInt(pool.size()));

        recordUsed(siteId, chosen);
        return chosen.toImage();
    }

    // ── Query building ──────────────────────────────────────────────────────────
    private List<String> buildQueries(String title, String mainKeyword, List<String> subKeywords) {
        List<String> queries = new ArrayList<>();
        JsonNode ai = claude.isConfigured() ? claude.completeJson(400,
                "You suggest 4 short (2-4 word) stock-photo search queries for a B2B software/tech blog. "
                        + "Only generic professional scenes (developers, teams, offices, laptops). "
                        + "Return ONLY JSON: {\"queries\":[\"...\"]}.",
                "Blog title: " + title + "\nMain keyword: " + mainKeyword + "\nReturn JSON only.") : null;
        if (ai != null) {
            ai.path("queries").forEach(n -> queries.add(n.asText("")));
        }
        // Fallback + always include a couple of safe generics.
        queries.add(cleanQuery(mainKeyword));
        queries.add("software development team");
        queries.add("developers working office");
        // Clean, dedupe, cap at 5.
        List<String> out = new ArrayList<>();
        for (String q : queries) {
            String c = cleanQuery(q);
            if (!c.isBlank() && !out.contains(c)) {
                out.add(c);
            }
            if (out.size() >= 5) {
                break;
            }
        }
        return out;
    }

    private String cleanQuery(String q) {
        if (q == null) {
            return "";
        }
        List<String> words = new ArrayList<>();
        for (String w : q.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").split("\\s+")) {
            if (!w.isBlank() && !BAD_QUERY_WORDS.contains(w)) {
                words.add(w);
            }
            if (words.size() >= 6) {
                break;
            }
        }
        return String.join(" ", words).trim();
    }

    // ── Search + scoring ────────────────────────────────────────────────────────
    private List<JsonNode> search(String query, int page) {
        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromUriString("https://api.pexels.com/v1/search")
                    .queryParam("query", query).queryParam("orientation", "landscape")
                    .queryParam("size", "large").queryParam("per_page", 30).queryParam("page", page)
                    .queryParam("locale", "en-US");
            JsonNode res = http.get().uri(uri.build().toUri())
                    .header("Authorization", props.getPexelsApiKey())
                    .retrieve().body(JsonNode.class);
            List<JsonNode> out = new ArrayList<>();
            if (res != null) {
                res.path("photos").forEach(out::add);
            }
            return out;
        } catch (Exception e) {
            log.warn("[seofarm] Pexels search '{}' failed: {}", query, e.getMessage());
            return List.of();
        }
    }

    private Candidate score(JsonNode photo, String query, String mainKeyword, List<String> subKeywords,
                            Set<String> usedIds, Set<String> usedAltSigs) {
        Candidate c = new Candidate();
        c.id = photo.path("id").asText();
        c.query = query;
        c.alt = photo.path("alt").asText("");
        c.avgColor = photo.path("avg_color").asText(null);
        c.photographer = photo.path("photographer").asText(null);
        c.photographerUrl = photo.path("photographer_url").asText(null);
        c.pexelsUrl = photo.path("url").asText(null);
        c.width = photo.path("width").asInt(0);
        c.height = photo.path("height").asInt(0);
        JsonNode src = photo.path("src");
        c.url = firstNonBlank(src.path("large2x").asText(null), src.path("large").asText(null), src.path("original").asText(null));
        c.medium = src.path("medium").asText(null);
        c.small = src.path("small").asText(null);
        c.altSig = altSignature(c.alt);

        if (usedIds.contains(c.id) || (c.altSig != null && usedAltSigs.contains(c.altSig)) || c.url == null) {
            c.score = -9999;
            return c;
        }
        int s = 0;
        double ratio = c.height > 0 ? (double) c.width / c.height : 0;
        if (ratio >= 1.55 && ratio <= 1.95) s += 12;
        else if (ratio >= 1.35 && ratio <= 2.2) s += 7;
        String altLow = c.alt.toLowerCase();
        for (String tok : query.split(" ")) {
            if (!tok.isBlank() && altLow.contains(tok)) s += 5;
        }
        if (mainKeyword != null) {
            for (String tok : mainKeyword.toLowerCase().split(" ")) {
                if (!tok.isBlank() && altLow.contains(tok)) s += 2;
            }
        }
        for (String g : GOOD_TERMS) if (altLow.contains(g)) s += 3;
        for (String b : BAD_TERMS) if (altLow.contains(b)) s -= 30;
        if (c.alt.length() >= 20) s += 3;
        if (c.width >= 1600) s += 8; else if (c.width >= 1200) s += 5;
        if (c.height >= 900) s += 5; else if (c.height >= 700) s += 3;
        if (c.photographer != null) s += 1;
        if (src.hasNonNull("large2x")) s += 4; else if (src.hasNonNull("large")) s += 2;
        c.score = s;
        return c;
    }

    private void recordUsed(String siteId, Candidate c) {
        try {
            SeoUsedImageEntity e = new SeoUsedImageEntity();
            e.setSiteId(siteId);
            e.setImageId(c.id);
            e.setUsedAt(Instant.now());
            e.setQuery(c.query);
            e.setAlt(c.alt);
            e.setAltSignature(c.altSig);
            e.setPhotographer(c.photographer);
            usedRepo.save(e);
        } catch (Exception ignored) {
            // best-effort dedup record
        }
    }

    private String altSignature(String alt) {
        if (alt == null || alt.isBlank()) {
            return null;
        }
        return new TreeSet<>(Arrays.asList(alt.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").trim().split("\\s+")))
                .stream().filter(w -> w.length() > 2).reduce((a, b) -> a + " " + b).orElse(null);
    }

    private String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static class Candidate {
        String id, query, alt, avgColor, photographer, photographerUrl, pexelsUrl, url, medium, small, altSig;
        int width, height, score;

        SeoBlogImage toImage() {
            Long imgId = null;
            try {
                imgId = Long.parseLong(id);
            } catch (Exception ignored) {
            }
            return SeoBlogImage.builder()
                    .id(imgId).url(url).medium(medium).small(small)
                    .photographer(photographer).photographerUrl(photographerUrl)
                    .alt(alt).pexelsUrl(pexelsUrl).build();
        }
    }
}
