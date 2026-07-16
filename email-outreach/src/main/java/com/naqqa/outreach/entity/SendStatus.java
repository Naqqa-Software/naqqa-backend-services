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
    /** The lead replied to this message (stops the sequence). */
    REPLIED,
    /** The lead unsubscribed (stops the sequence). */
    UNSUBSCRIBED
}
