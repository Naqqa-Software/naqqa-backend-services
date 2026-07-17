package com.naqqa.outreach.repository;

import com.naqqa.outreach.entity.SendStatus;
import com.naqqa.outreach.entity.SentEmailEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface SentEmailRepository extends MongoRepository<SentEmailEntity, String> {

    /** Daily-cap counting: how many were sent by this profile in a time window. */
    long countByProfileKeyAndStatusAndSentAtBetween(String profileKey, SendStatus status, Instant from, Instant to);

    /** Dedup: has this company already been contacted (any status)? */
    boolean existsByCompanyId(String companyId);

    boolean existsByToEmail(String toEmail);

    /** A lead's sequence, newest step first (for follow-up scheduling + reply matching). */
    List<SentEmailEntity> findByThreadKeyOrderBySequenceStepDesc(String threadKey);

    List<SentEmailEntity> findByMessageId(String messageId);

    List<SentEmailEntity> findByProfileKeyAndStatus(String profileKey, SendStatus status);

    List<SentEmailEntity> findByToEmail(String toEmail);

    /** Case-insensitive recipient lookup — reply/bounce From addresses vs mixed-case stored toEmail. */
    List<SentEmailEntity> findByToEmailIgnoreCase(String toEmail);

    List<SentEmailEntity> findByThreadKey(String threadKey);
}
