package com.naqqa.outreach.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
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

    /**
     * Secret used to encrypt sender app passwords at rest (AES-GCM; the key is the SHA-256 of this
     * string). Inject from env/secrets. If blank, passwords are stored as plaintext (a warning is
     * logged) — set it in every environment that persists real credentials.
     */
    private String secretKey;

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
     * Catch-up tick: how often (ms) to re-check whether the day's sends still need to run — covers
     * starting the app mid-day and recovering from downtime inside the working window. Idempotent
     * (sends only up to the per-profile daily cap, which persists in {@code outreach_account_state}).
     */
    private long catchUpTickMs = 1800000; // 30 min

    /** Directory for the per-profile daily log files (also mirrored to the {@code outreach_logs} collection). */
    private String logDir = "logs/outreach";

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

    /**
     * Sender profiles (the "email users") provisioned from the HOST backend's config — e.g.
     * {@code naqqa.outreach.profiles[0].key=alex}, with the mailbox + Gmail app password injected
     * from environment/secrets. On startup {@code OutreachProfileConfigurer} upserts each into the
     * {@code outreach_profiles} collection (a profile with a blank mailbox or app password is
     * skipped). This keeps sender accounts configurable from the backend instead of hardcoded in
     * the library or set only via the API.
     */
    private List<ProfileConfig> profiles = new ArrayList<>();

    /** One configured sender mailbox. Mirrors the writable fields of {@code OutreachProfileEntity}. */
    @Getter
    @Setter
    public static class ProfileConfig {
        private String key;
        private String fromEmail;
        private String fromName;
        private String signature;
        private String appPassword;
        private String smtpHost;
        private Integer smtpPort;
        private String imapHost;
        private Integer imapPort;
        /** Flat fallback cap used ONLY when no warm-up schedule is configured. */
        private Integer dailyLimit;
        /**
         * Path to a warm-up JSON file — {@code {"startDate":"dd/MM/yyyy","schedule":[1,0,1,...]}} —
         * where {@code schedule[n]} is the max sends on working-day n after {@code startDate} (the
         * last value is the steady post-warm-up cap). Accepts {@code classpath:...} or {@code file:...}.
         * Takes precedence over the inline warm-up fields below. This is the warm-up ramp.
         */
        private String warmupFile;
        /** Inline warm-up start (ISO yyyy-MM-dd), alternative to {@link #warmupFile}. */
        private String warmupStartDate;
        /** Inline warm-up per-working-day caps, alternative to {@link #warmupFile}. */
        private List<Integer> warmupSchedule;
        private Boolean enabled;
    }
}
