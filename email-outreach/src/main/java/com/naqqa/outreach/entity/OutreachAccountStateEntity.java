package com.naqqa.outreach.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-profile runtime state that used to live in the {@code data/*-{alex,radu}.json} files:
 * the daily send counter, the rolling send/bounce window, per-day bounce counts, pause window,
 * and processed IMAP UIDs. One document per profile key.
 */
@Document("outreach_account_state")
@Getter
@Setter
public class OutreachAccountStateEntity {

    @Id
    private String id;

    @Indexed(unique = true)
    private String profileKey;

    /** Daily send counter (auto-resets when the date changes). */
    private String counterDate; // DD/MM/YYYY
    private int sentToday;

    /** Follow-ups sent today (subset of sentToday) — caps the daily follow-up allocation. */
    private int followupsSentToday;

    /** Rolling last-100 outcomes ("sent" / "bounced") for bounce-rate. */
    private List<String> recentEmails = new ArrayList<>();

    /** date (DD/MM/YYYY) -> hard bounces that day. */
    private Map<String, Integer> dailyBounces = new LinkedHashMap<>();

    /** Account paused until this instant (bounce protection). */
    private Instant pausedUntil;

    /** Last IMAP UIDs already processed for bounces (kept to last 1000). */
    private List<Long> processedUids = new ArrayList<>();
}
