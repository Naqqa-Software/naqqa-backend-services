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

    /** Ollama endpoint + model used to generate emails (kept from the script). */
    private String ollamaUrl = "http://localhost:11434";
    private String ollamaModel = "gpt-oss:20b";

    /**
     * Optional bearer token sent as {@code Authorization: Bearer <token>} on every Ollama call — set
     * this to match the token your Ollama reverse-proxy expects (remote Ollama behind auth). Blank =
     * no auth header (local Ollama).
     */
    private String ollamaToken;

    /**
     * Serialize all Ollama calls across both profiles (the script's global chat-lock) and wait this
     * long after each generation (the script's 20 s cooldown) — protects the local model. Turn the
     * lock OFF (Ollama queues requests itself) so a stuck generation can never block others.
     */
    private boolean ollamaChatLock = true;
    private long ollamaCooldownMs = 20000;

    /** Read timeout (ms) for an Ollama call — a hung/unreachable model fails fast instead of blocking. */
    private long ollamaTimeoutMs = 120000; // 2 min (allow slow generation, but never hang forever)

    /** Apollo.io API key (decision-maker email finder). */
    private String apolloApiKey;

    /**
     * Secret used to encrypt sender app passwords at rest (AES-GCM; the key is the SHA-256 of this
     * string). Inject from env/secrets. If blank, passwords are stored as plaintext (a warning is
     * logged) — set it in every environment that persists real credentials.
     */
    private String secretKey;

    /** Working window (senders only run inside this, in the configured timezone). End hour is exclusive. */
    private String timezone = "Europe/Chisinau";
    private int workStartHour = 9;
    private int workEndHour = 23; // sends until 23:00 (11 PM); end hour is exclusive

    /** Randomized delay between sends (seconds). */
    private int minDelaySeconds = 180;
    private int maxDelaySeconds = 300;

    /**
     * Optional opt-out footer appended after the signature. Blank by default because the sender
     * signature already ends with the "reply unsubscribe" line. A reply containing "unsubscribe" is
     * detected and stops the sequence.
     */
    private String unsubscribeFooter = "";

    /** Master switch for the AI follow-up sequence (threaded replies). */
    private boolean followupsEnabled = true;

    /** Share of the daily cap reserved for follow-ups (0.30 = 30%); the rest goes to new leads. */
    private double followupRatio = 0.30;

    /** Follow-up cadence: days after the previous step for each level (index 0 = level 1, …). */
    private List<Integer> followupDelaysDays = List.of(3, 6, 10);
    /** Max follow-up levels (1..maxFollowups). */
    private int maxFollowups = 3;

    /** Cron for the daily kickoff (default 09:00). */
    private String dailyCron = "0 0 9 * * *";

    /**
     * Catch-up tick: how often (ms) to re-check whether the day's sends still need to run — covers
     * starting the app mid-day and recovering from downtime inside the working window. Idempotent
     * (sends only up to the per-profile daily cap, which persists in {@code outreach_account_state}).
     */
    private long catchUpTickMs = 1800000; // 30 min

    /**
     * Dedicated inbox-sync cadence (ms) — how often to poll each mailbox for replies / bounces,
     * independent of the send flow. Default 2 h. Inbox sync also runs on every startup + catch-up.
     */
    private long inboxSyncTickMs = 7200000; // 2 h

    /** How far back the routine inbox sync scans for replies/bounces (hours). */
    private int inboxWindowHours = 48;

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

    /** How often the website-info backfill tick fires (scrapes companies that have an email but no info). */
    private long scrapeTickMs = 60000;

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
