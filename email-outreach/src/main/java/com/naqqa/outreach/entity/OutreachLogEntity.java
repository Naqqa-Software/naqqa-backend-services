package com.naqqa.outreach.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One console log line from the outreach engine, tagged with the sender profile (or {@code leadgen}
 * for the shared Apollo extraction / {@code system} otherwise). Mirrors the per-profile daily
 * {@code .txt} files so logs are queryable from the DB too.
 */
@Document("outreach_logs")
@Getter
@Setter
public class OutreachLogEntity {

    @Id
    private String id;

    /** Sender profile key, or "leadgen" / "system". */
    @Indexed
    private String profileKey;

    /** yyyy-MM-dd (UTC) — the daily bucket, matches the .txt filename. */
    @Indexed
    private String day;

    private String level;
    private String logger;
    private String message;

    @Indexed
    private Instant createdAt;
}
