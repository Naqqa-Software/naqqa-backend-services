package com.naqqa.seofarm.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verbatim port of the keyword vocabularies from the bot's dataforseo.js. */
public final class SeoKeywordConstants {

    private SeoKeywordConstants() {
    }

    /** DataForSEO/SerpAPI location code → geo code (UPPERCASE for trends, lowercase gl otherwise). */
    public static final Map<Integer, String> GEO_UPPER = Map.of(
            2826, "GB", 2840, "US", 2276, "DE", 2250, "FR", 2804, "UA", 2616, "PL", 2642, "RO", 2498, "MD");
    public static final Map<Integer, String> GEO_LOWER = Map.of(
            2826, "gb", 2840, "us", 2276, "de", 2250, "fr", 2804, "ua", 2616, "pl", 2642, "ro", 2498, "md");

    /** A keyword must contain at least one of these to be a relevant IT-services topic. */
    public static final List<String> SERVICE_TERMS = List.of(
            "outsourc", "outstaffing", "staff augmentation", "dedicated team", "dedicated developer",
            "nearshore", "offshore", "remote team", "remote developer", "remote hiring", "it team",
            "it staffing", "it recruitment", "it service", "it support", "it company", "software company",
            "tech company", "development company", "software house", "hire developer", "hire software",
            "hire programmer", "find developer", "developer for hire", "developers for hire",
            "software engineer for hire", "cost of", "salary", "rate", "price", "pricing", "budget",
            "cost saving", "cheaper", "affordable", "hourly rate", "day rate", "developer rate",
            "mvp development", "app development", "software development", "custom software", "web development",
            "mobile development", "product development", "saas development", "platform development",
            "api development", "build team", "scale team", "engineering team", "development team",
            "team extension", "augment team", "expand team", "startup", "tech startup", "b2b",
            "enterprise software", "digital transformation", "digital agency", "react developer",
            "node developer", "python developer", "java developer", "full stack developer",
            "backend developer", "frontend developer", "mobile app developer", "ios developer",
            "android developer", "devops engineer", "cloud engineer", "data engineer", "eastern europe",
            "uk developer", "london developer", "europe developer", "moldova", "romania", "poland", "ukraine");

    /** Any of these disqualifies a keyword (news/entertainment/off-topic). */
    public static final List<String> BLACKLIST = List.of(
            "news", "today", "latest", "update", "breaking", "headline", "gossip", "rumor", "scandal",
            "controversy", "drama", "celebrity", "influencer", "youtuber", "tiktoker", "game", "gaming",
            "esports", "xbox", "playstation", "movie", "tv show", "netflix", "series", "episode", "recipe",
            "food", "restaurant", "cooking", "fashion", "style", "outfit", "clothing", "weather", "forecast",
            "temperature", "score", "match", "football", "soccer", "cricket", "tennis", "stock price",
            "share price", "crypto price", "bitcoin price");

    /** Ignored when computing a keyword's token signature (for similarity). */
    public static final Set<String> SIMILARITY_STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "best", "by", "company", "companies", "firm", "firms",
            "for", "from", "guide", "how", "in", "into", "is", "it", "leading", "list", "near", "of", "on",
            "partner", "partners", "provider", "providers", "service", "services", "the", "to", "top",
            "trusted", "uk", "united", "kingdom", "vs", "what", "why", "with", "2024", "2025", "2026");

    /** Round-robin diversity buckets for the final top-10 selection. */
    public static final List<Bucket> DIVERSITY_BUCKETS = List.of(
            new Bucket("outsourcing", List.of("outsourc", "it outsourcing", "software outsourcing")),
            new Bucket("nearshore-offshore", List.of("nearshore", "offshore", "eastern europe", "moldova", "romania", "poland", "ukraine")),
            new Bucket("hiring-developers", List.of("hire", "developer for hire", "developers for hire", "find developer", "remote developer", "uk developer")),
            new Bucket("dedicated-teams", List.of("dedicated team", "dedicated developer", "development team", "engineering team", "team extension", "scale team", "build team")),
            new Bucket("staff-augmentation", List.of("staff augmentation", "outstaffing", "it staffing", "it recruitment", "augment team")),
            new Bucket("cost-rates", List.of("cost", "rate", "salary", "price", "pricing", "budget", "affordable", "cheaper", "hourly rate", "day rate")),
            new Bucket("software-development", List.of("software development", "custom software", "product development", "enterprise software")),
            new Bucket("web-mobile-apps", List.of("web development", "mobile development", "app development", "ios developer", "android developer")),
            new Bucket("startup-mvp-saas", List.of("startup", "mvp", "saas", "platform development", "b2b")),
            new Bucket("tech-stack", List.of("react developer", "node developer", "python developer", "java developer", "full stack", "backend developer", "frontend developer", "devops engineer", "cloud engineer", "data engineer")));

    /** Sub-keyword rejects (unwanted geographies + analyst/regulator references). */
    public static final List<String> SUB_KW_BLACKLIST = List.of(
            "in india", "in pakistan", "in philippines", "in vietnam", "in china", "in bangladesh",
            "in australia", "in canada", "in new zealand", "in south africa", "in nigeria", "in new york",
            "in california", "in usa", "in us", "in kochi", "in brisbane", "in perth", "in melbourne",
            "in wisconsin", "in toronto", "gartner", "rbi guidelines", "rbi");

    public record Bucket(String name, List<String> terms) {
    }
}
