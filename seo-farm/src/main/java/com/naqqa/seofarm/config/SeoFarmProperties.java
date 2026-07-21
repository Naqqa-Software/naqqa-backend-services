package com.naqqa.seofarm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/**
 * Config for the naqqa-seo-farm library. Registered by {@link SeoFarmAutoConfiguration}'s
 * {@code @EnableConfigurationProperties} — NOT a {@code @Component} (that would double-register it
 * once the host component-scans {@code com.naqqa.seofarm}). Keyword research uses SerpAPI, images use
 * Pexels, and generation uses the host's Anthropic client ({@code anthropic.api.*}).
 */
@ConfigurationProperties(prefix = "naqqa.seofarm")
@Getter
@Setter
public class SeoFarmProperties {

    /** Claude model for keyword filtering, competitor analysis and blog generation. */
    private String model = "claude-sonnet-4-6";

    /**
     * SerpAPI key(s) — Google SERP / autocomplete / trends + competitor analysis. Supports MULTIPLE
     * comma-separated keys (multiple accounts): when one runs out of searches (429) the client fails
     * over to the next. Example: {@code SEOFARM_SERPAPI_KEY=key1,key2}.
     */
    private String serpApiKey = "";
    /** Pexels API key for blog images. */
    private String pexelsApiKey = "";
    /** Whether to show the Pexels photographer attribution in generated HTML. */
    private boolean showImageAttribution = false;

    /**
     * Public base URL of THIS backend (e.g. https://api.example.com), used to inject the
     * naqqa-analytics tracker snippet into generated blog HTML. Empty = no beacon injected
     * (server-side view tracking still works).
     */
    private String analyticsBaseUrl = "";

    /** Master switch for the generation scheduler (manual "Generate now" still works when false). */
    private boolean scheduleEnabled = true;
    /** Daily generation cron (default 10:00). */
    private String scheduleCron = "0 0 10 * * *";
    /** Max blogs auto-generated per site per scheduler run. */
    private int dailyLimitPerSite = 1;
    /** Days between keyword re-extraction per site. */
    private int keywordRefreshDays = 7;

    /**
     * Max root-terms sampled per extraction (each costs ~3 SerpAPI searches). Caps the cost of one
     * extraction so free SerpAPI plans (250/mo) aren't drained in a single run; a random subset is
     * chosen each time so all root-terms get covered over successive runs. 0 = use all root-terms.
     */
    private int maxRootTermsPerExtraction = 12;

    /** The configured SerpAPI keys, in failover order (comma-separated {@link #serpApiKey}, blanks dropped). */
    public List<String> getSerpApiKeys() {
        if (serpApiKey == null || serpApiKey.isBlank()) {
            return List.of();
        }
        return Arrays.stream(serpApiKey.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
