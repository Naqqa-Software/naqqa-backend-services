package com.naqqa.outreach.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One outbound email (tracking record) — powers the admin "Emails sent" tab and the follow-up
 * sequence state. A lead's sequence is ONE row: follow-ups increment {@code followupCount} and are
 * appended to {@code followups} (they are NOT separate rows). {@code threadKey} groups the lead.
 */
@Document("outreach_sent_emails")
@Getter
@Setter
public class SentEmailEntity {

    @Id
    private String id;

    @Indexed
    private String profileKey;

    /** The sender mailbox this was sent from (e.g. alexandru.latcovschi@naqqateams.com). */
    private String fromEmail;

    /** it_companies document id this lead came from. */
    @Indexed
    private String companyId;
    private String companyName;

    @Indexed
    private String toEmail;
    private String toName;
    private String website;
    private String city;

    private String subject;
    private String body;

    /** 1 = initial, 2.. = follow-ups. */
    private int sequenceStep;

    /** Number of follow-ups sent in this thread (kept on the single row, not new rows). */
    private int followupCount;
    /** Timestamp of the last message sent (initial or follow-up) — drives the follow-up due gap. */
    private Instant lastSentAt;
    /** The follow-up bodies sent, oldest first (thread history for the AI + record). */
    private List<String> followups = new ArrayList<>();

    /** The prospect's reply text (saved on RESPONDED) so you can read what they said. */
    private String responseText;
    /** Free-text notes the admin adds about this lead / response. */
    private String notes;

    /** Groups all messages of one lead's sequence (e.g. profileKey + companyId). */
    @Indexed
    private String threadKey;

    /** RFC Message-ID we generated for this send (used to thread follow-ups + match replies). */
    private String messageId;
    /** Message-ID this send is a reply to (for follow-ups). */
    private String inReplyTo;

    @Indexed
    private SendStatus status;
    private String error;

    @Indexed
    private Instant sentAt;
    private Instant repliedAt;
    private Instant bouncedAt;

    /** Last outbound activity (last follow-up if any, else the initial send) — for follow-up gaps. */
    public Instant getLastActivity() {
        return lastSentAt != null ? lastSentAt : sentAt;
    }
}
