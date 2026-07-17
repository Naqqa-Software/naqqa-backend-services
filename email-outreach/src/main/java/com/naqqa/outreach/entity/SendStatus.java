package com.naqqa.outreach.entity;

/** Lifecycle of a single outbound outreach email. */
public enum SendStatus {
    /** Successfully handed to the SMTP server. */
    SENT,
    /** Sending failed (SMTP error, validation, etc.). */
    FAILED,
    /** Deliberately not sent (safety gate, AI shouldSend=false, suppressed). */
    SKIPPED,
    /** A bounce was later detected for this message. */
    BOUNCED,
    /** The lead responded to this message (stops the sequence). */
    RESPONDED,
    /** Response triaged as positive (interested). */
    POSITIVE,
    /** Response triaged as negative (not interested). */
    NEGATIVE,
    /** Legacy reply status (kept for existing records; new replies use RESPONDED). */
    REPLIED,
    /** The lead unsubscribed (stops the sequence). */
    UNSUBSCRIBED
}
