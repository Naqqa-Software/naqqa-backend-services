package com.naqqa.seofarm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.seofarm.config.SeoFarmProperties;
import com.naqqa.seofarm.entity.SeoKeywordBatchEntity;
import com.naqqa.seofarm.entity.SeoUsedKeywordEntity;
import com.naqqa.seofarm.model.SeoKeywordCluster;
import com.naqqa.seofarm.model.SeoSite;
import com.naqqa.seofarm.repository.SeoKeywordBatchRepository;
import com.naqqa.seofarm.repository.SeoUsedKeywordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Keyword research — faithful Java port of the bot's dataforseo.js. Weekly extraction pulls SerpAPI
 * (related/PAA + autocomplete + trends) across the site's root terms, filters to relevant IT topics,
 * dedups against already-used keywords (exact + token-signature + Jaccard≥0.72 similarity), asks
 * Claude to pick the top 10, fetches sub-keywords, and stores clusters. {@link #nextCluster} pulls
 * the next unused topic for a blog.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KeywordResearchService {

    private static final double SIMILARITY_THRESHOLD = 0.72;

    private final SerpApiClient serp;
    private final SeoClaudeClient claude;
    private final SeoKeywordBatchRepository batches;
    private final SeoUsedKeywordRepository usedRepo;
    private final SeoFarmProperties props;

    // ── Scheduling ──────────────────────────────────────────────────────────────
    public boolean isExtractionDue(String siteId) {
        List<SeoKeywordBatchEntity> b = batches.findBySiteIdOrderByExtractedAtDesc(siteId);
        if (b.isEmpty()) {
            return true;
        }
        Instant last = b.get(0).getExtractedAt();
        return last == null || ChronoUnit.DAYS.between(last, Instant.now()) >= props.getKeywordRefreshDays();
    }

    // ── Extraction pipeline ─────────────────────────────────────────────────────
    public List<SeoKeywordCluster> extractAndStore(SeoSite site) {
        serp.resetQuotaFlag(); // retry a possibly-renewed SerpAPI quota
        String gl = SeoKeywordConstants.GEO_LOWER.getOrDefault(site.locationCode(), "us");
        String geoUpper = SeoKeywordConstants.GEO_UPPER.getOrDefault(site.locationCode(), "US");
        String hl = site.languageCode() == null ? "en" : site.languageCode();

        // 1. Fetch all three SerpAPI sources across a capped, randomised subset of root terms
        //    (keeps one extraction cheap on free SerpAPI plans; different terms each run).
        LinkedHashSet<String> raw = new LinkedHashSet<>();
        for (String term : pickRootTerms(site)) {
            raw.addAll(serp.relatedAndPaa(term, gl, hl));
            raw.addAll(serp.autocomplete(term, gl, hl));
            raw.addAll(serp.trends(term, geoUpper, hl));
        }

        // 2. Filter to relevant IT keywords.
        List<String> relevant = raw.stream().map(String::trim).filter(s -> !s.isEmpty())
                .filter(this::isItKeyword).toList();

        // 3. Partition against used keywords.
        Set<String> used = usedNormalized(site.id());
        Set<String> usedSignatures = usedSignatures(site.id());
        Partition part = partition(relevant, used, usedSignatures);
        List<String> candidates = part.fresh.isEmpty() ? part.similar : part.fresh;

        // 4. Claude picks the top 10 (fallback = heuristic).
        List<String> top = filterWithAI(candidates, used);
        if (top.isEmpty()) {
            top = fallbackKeywords(candidates);
        }

        // 5. Sub-keywords per main keyword → clusters.
        List<SeoKeywordCluster> clusters = new ArrayList<>();
        for (String kw : top) {
            clusters.add(new SeoKeywordCluster(kw, fetchSubKeywords(kw, gl, hl)));
        }

        // 6. Store the batch.
        SeoKeywordBatchEntity batch = new SeoKeywordBatchEntity();
        batch.setSiteId(site.id());
        batch.setExtractedAt(Instant.now());
        batch.setSource(candidates.isEmpty() ? "fallback" : "serpapi");
        batch.setCount(clusters.size());
        batch.setClusters(clusters);
        batches.save(batch);
        log.info("[seofarm] {} keyword extraction stored {} clusters.", site.id(), clusters.size());
        return clusters;
    }

    /** A random, capped subset of the site's root-terms (all terms rotate in over successive runs). */
    private List<String> pickRootTerms(SeoSite site) {
        List<String> all = new ArrayList<>(site.rootTerms());
        int max = props.getMaxRootTermsPerExtraction();
        if (max <= 0 || all.size() <= max) {
            return all;
        }
        Collections.shuffle(all);
        return all.subList(0, max);
    }

    private List<String> fetchSubKeywords(String keyword, String gl, String hl) {
        LinkedHashSet<String> raw = new LinkedHashSet<>();
        raw.addAll(serp.autocomplete(keyword, gl, hl));
        raw.addAll(serp.relatedAndPaa(keyword, gl, hl));
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            String t = s.trim();
            if (!t.isEmpty() && isQualitySubKeyword(t, keyword) && !out.contains(t)) {
                out.add(t);
            }
            if (out.size() >= 15) {
                break;
            }
        }
        return out;
    }

    // ── Next topic for a blog ───────────────────────────────────────────────────
    public SeoKeywordCluster nextCluster(String siteId) {
        Set<String> used = usedNormalized(siteId);
        Set<String> usedSignatures = usedSignatures(siteId);
        List<SeoKeywordCluster> all = new ArrayList<>();
        for (SeoKeywordBatchEntity b : batches.findBySiteIdOrderByExtractedAtDesc(siteId)) {
            if (b.getClusters() != null) {
                all.addAll(b.getClusters());
            }
        }
        SeoKeywordCluster firstSimilar = null;
        Set<String> seen = new HashSet<>();
        for (SeoKeywordCluster c : all) {
            String norm = normalize(c.getKeyword());
            if (norm.isBlank() || !seen.add(norm)) {
                continue;
            }
            if (used.contains(norm) || usedSignatures.contains(signature(c.getKeyword()))) {
                continue; // already used
            }
            if (isSimilarToUsed(c.getKeyword(), siteId)) {
                if (firstSimilar == null) {
                    firstSimilar = c;
                }
                continue;
            }
            return c; // fresh
        }
        return firstSimilar;
    }

    public void markUsed(String siteId, String keyword) {
        String norm = normalize(keyword);
        if (norm.isBlank() || usedRepo.existsBySiteIdAndKeyword(siteId, norm)) {
            return;
        }
        SeoUsedKeywordEntity e = new SeoUsedKeywordEntity();
        e.setSiteId(siteId);
        e.setKeyword(norm);
        e.setUsedAt(Instant.now());
        try {
            usedRepo.save(e);
        } catch (Exception ignored) {
            // race on the unique index — already marked
        }
    }

    // ── AI filter ───────────────────────────────────────────────────────────────
    private List<String> filterWithAI(List<String> candidates, Set<String> used) {
        if (candidates.isEmpty() || !claude.isConfigured()) {
            return List.of();
        }
        List<String> pool = candidates.size() > 60 ? candidates.subList(0, 60) : candidates;
        String system = """
                You select the 10 BEST blog-topic keywords for a software-development / IT-outsourcing
                company (Eastern Europe, targeting UK/US/DE buyers). HARD RULES:
                - Return EXACTLY 10 keywords, chosen ONLY from the candidate list (verbatim).
                - No duplicates and no two near-identical intents (max 2 per intent group).
                - Reject news, tutorials, job-seeker searches, gaming, specific tools, irrelevant locations.
                - Prefer commercial blog topics with clear buyer intent.
                Return ONLY JSON: {"selected": ["kw1", ..., "kw10"]}.""";
        String user = "Candidates:\n" + String.join("\n", pool)
                + "\n\nAlready used (avoid these and anything near-identical):\n" + String.join("\n", used)
                + "\n\nReturn JSON only.";
        JsonNode res = claude.completeJson(1024, system, user);
        if (res == null) {
            return List.of();
        }
        Set<String> lower = new HashSet<>();
        for (String c : candidates) {
            lower.add(c.toLowerCase());
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : res.path("selected")) {
            String kw = n.asText("").trim();
            if (!kw.isBlank() && lower.contains(kw.toLowerCase()) && !out.contains(kw)) {
                out.add(kw);
            }
        }
        return out.size() > 10 ? out.subList(0, 10) : out;
    }

    private List<String> fallbackKeywords(List<String> candidates) {
        List<String> priority = List.of("outsourc", "nearshore", "offshore", "staff augmentation",
                "outstaffing", "dedicated", "hire", "developer", "team", "cost", "rate", "mvp", "saas");
        List<String> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingInt(k -> {
            String low = k.toLowerCase();
            for (int i = 0; i < priority.size(); i++) {
                if (low.contains(priority.get(i))) {
                    return i;
                }
            }
            return 999;
        }));
        return selectDiverse(ranked, 10);
    }

    // ── Diversity selection ─────────────────────────────────────────────────────
    private List<String> selectDiverse(List<String> keywords, int max) {
        List<String> out = new ArrayList<>();
        Set<String> sigs = new HashSet<>();
        boolean progressed = true;
        while (out.size() < max && progressed) {
            progressed = false;
            for (SeoKeywordConstants.Bucket bucket : SeoKeywordConstants.DIVERSITY_BUCKETS) {
                if (out.size() >= max) {
                    break;
                }
                for (String kw : keywords) {
                    if (out.contains(kw)) {
                        continue;
                    }
                    String low = kw.toLowerCase();
                    if (bucket.terms().stream().anyMatch(low::contains)) {
                        String sig = signature(kw);
                        if (sigs.add(sig)) {
                            out.add(kw);
                            progressed = true;
                        }
                        break;
                    }
                }
            }
        }
        for (String kw : keywords) { // top up with anything left
            if (out.size() >= max) {
                break;
            }
            if (!out.contains(kw) && sigs.add(signature(kw))) {
                out.add(kw);
            }
        }
        return out;
    }

    // ── Partition / similarity ──────────────────────────────────────────────────
    private record Partition(List<String> fresh, List<String> similar) {
    }

    private Partition partition(List<String> keywords, Set<String> used, Set<String> usedSignatures) {
        List<String> fresh = new ArrayList<>();
        List<String> similar = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> usedList = new ArrayList<>(used);
        for (String kw : keywords) {
            String norm = normalize(kw);
            if (norm.isBlank() || !seen.add(norm)) {
                continue;
            }
            if (used.contains(norm) || usedSignatures.contains(signature(kw))) {
                continue; // exact/signature used
            }
            boolean sim = usedList.stream().anyMatch(u -> tokenSimilarity(kw, u) >= SIMILARITY_THRESHOLD);
            (sim ? similar : fresh).add(kw);
        }
        return new Partition(fresh, similar);
    }

    private boolean isSimilarToUsed(String keyword, String siteId) {
        return usedNormalized(siteId).stream().anyMatch(u -> tokenSimilarity(keyword, u) >= SIMILARITY_THRESHOLD);
    }

    private Set<String> usedNormalized(String siteId) {
        Set<String> s = new HashSet<>();
        for (SeoUsedKeywordEntity e : usedRepo.findBySiteId(siteId)) {
            s.add(e.getKeyword());
        }
        return s;
    }

    private Set<String> usedSignatures(String siteId) {
        Set<String> s = new HashSet<>();
        for (SeoUsedKeywordEntity e : usedRepo.findBySiteId(siteId)) {
            s.add(signature(e.getKeyword()));
        }
        return s;
    }

    // ── Text helpers (verbatim logic) ───────────────────────────────────────────
    private boolean isItKeyword(String kw) {
        String low = kw.toLowerCase();
        boolean hasService = SeoKeywordConstants.SERVICE_TERMS.stream().anyMatch(low::contains);
        boolean hasBlack = SeoKeywordConstants.BLACKLIST.stream().anyMatch(low::contains);
        return hasService && !hasBlack;
    }

    private boolean isQualitySubKeyword(String sub, String main) {
        if (!isItKeyword(sub)) {
            return false;
        }
        if (normalize(sub).equals(normalize(main))) {
            return false;
        }
        String low = sub.toLowerCase();
        if (SeoKeywordConstants.SUB_KW_BLACKLIST.stream().anyMatch(low::contains)) {
            return false;
        }
        return tokenSimilarity(sub, main) < SIMILARITY_THRESHOLD;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase()
                .replaceAll("[’'`]", "")
                .replace("&", " and ")
                .replaceAll("[^a-z0-9\\s-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String singularize(String w) {
        if (w.endsWith("ies") && w.length() > 4) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.endsWith("s") && !w.endsWith("ss") && w.length() > 4) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    private List<String> tokens(String kw) {
        List<String> out = new ArrayList<>();
        for (String t : normalize(kw).split(" ")) {
            if (t.isBlank()) {
                continue;
            }
            String s = singularize(t);
            if (!SeoKeywordConstants.SIMILARITY_STOP_WORDS.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private String signature(String kw) {
        return new TreeSet<>(tokens(kw)).stream().reduce((a, b) -> a + " " + b).orElse("");
    }

    private double tokenSimilarity(String a, String b) {
        Set<String> at = new HashSet<>(tokens(a));
        Set<String> bt = new HashSet<>(tokens(b));
        if (at.isEmpty() || bt.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(at);
        inter.retainAll(bt);
        Set<String> union = new HashSet<>(at);
        union.addAll(bt);
        return (double) inter.size() / union.size();
    }
}
