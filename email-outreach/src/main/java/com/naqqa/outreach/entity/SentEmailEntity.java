package com.naqqa.outreach.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One outbound email (tracking record) — powers the admin "Emails sent" tab and the follow-up
 * sequence state. A lead's sequence is grouped by {@code threadKey}; {@code sequenceStep} is the
 * 1-based step number (1 = initial email, 2+ = follow-ups sent as replies in the same thread).
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
}
