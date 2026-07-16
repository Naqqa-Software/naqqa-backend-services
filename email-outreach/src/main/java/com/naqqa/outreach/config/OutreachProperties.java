package com.naqqa.outreach.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Global (non per-profile) outreach configuration. Per-sender settings live in
 * {@code outreach_profiles} documents; these are engine-wide knobs.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "naqqa.outreach")
public class OutreachProperties {

    /** Master switch for the daily scheduler. */
    private boolean enabled = true;

    /** Local Ollama endpoint + model used to generate emails (kept from the script). */
    private String ollamaUrl = "http://localhost:11434";
    private String ollamaModel = "gpt-oss:20b";

    /**
     * Serialize all Ollama calls across both profiles (the script's global chat-lock) and wait this
     * long after each generation (the script's 20 s cooldown) — protects the local model.
     */
    private long ollamaCooldownMs = 20000;

    /** Apollo.io API key (decision-maker email finder). */
    private String apolloApiKey;

    /** Working window (senders only run inside this, in the configured timezone). */
    private String timezone = "Europe/Chisinau";
    private int workStartHour = 9;
    private int workEndHour = 18;

    /** Randomized delay between sends (seconds). */
    private int minDelaySeconds = 180;
    private int maxDelaySeconds = 300;

    /**
     * Master switch for the follow-up sequence. OFF for now — send a single initial email only, no
     * step-2/step-3 follow-ups. Flip to true to re-enable the cadence below.
     */
    private boolean followupsEnabled = false;

    /** Follow-up cadence: days after the previous step for each follow-up (max = maxFollowups). */
    private List<Integer> followupDelaysDays = List.of(3, 7);
    private int maxFollowups = 2;

    /** Cron for the daily kickoff (default 09:00). */
    private String dailyCron = "0 0 9 * * *";

    /**
     * Email extraction (Apollo) runs continuously and independently of the daily send cap —
     * it fills the enriched pool up to your Apollo API limits. Sending then drains that pool.
     */
    private boolean extractionEnabled = true;
    /** Companies enriched per tick. */
    private int extractionBatchSize = 20;
    /** Delay between Apollo lookups (paces the API). */
    private long extractionDelayMs = 1500;
    /** How often the extraction tick fires. */
    private long extractionTickMs = 30000;

    /** Optional outbound HTTP proxy for the jsoup company-info scraper. */
    private String proxyHost;
    private Integer proxyPort;
}
