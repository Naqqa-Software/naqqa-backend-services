package com.naqqa.outreach.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A configured sender profile (formerly the hardcoded consts at the top of start_alex.js /
 * start_radu.js). Managed from naqqa-os-backend. One document per warmed Gmail mailbox.
 */
@Document("outreach_profiles")
@Getter
@Setter
public class OutreachProfileEntity {

    @Id
    private String id;

    /** Stable key used in code/logs/state (e.g. "alex", "radu"). */
    @Indexed(unique = true)
    private String key;

    private String fromEmail;
    private String fromName;
    private String signature;

    /**
     * Gmail App Password for SMTP/IMAP. Should be stored encrypted at rest / injected from a
     * secret — never logged. (Phase 1 stores it here; encryption is a follow-up.)
     */
    private String appPassword;

    private String smtpHost = "smtp.gmail.com";
    private int smtpPort = 465;
    private String imapHost = "imap.gmail.com";
    private int imapPort = 993;

    /** Warm-up: start date + per-working-day send caps (index = working-day number − 1). */
    private LocalDate warmupStartDate;
    private List<Integer> warmupSchedule = new ArrayList<>();

    /** Fallback daily cap when no warm-up schedule applies. */
    private Integer dailyLimit;

    /** Optional Google Sheet id to mirror the send log (kept from the original script). */
    private String googleSheetId;

    private boolean enabled = true;

    private Instant createdAt;
    private Instant updatedAt;
}
