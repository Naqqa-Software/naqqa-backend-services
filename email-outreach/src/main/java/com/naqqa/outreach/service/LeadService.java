package com.naqqa.outreach.service;

import com.naqqa.outreach.entity.LeadEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Lead lifecycle over the shared {@code it_companies} collection. Two independent claims:
 * <ul>
 *   <li><b>extraction</b>: DEFAULT → ENRICHING → ENRICHED (email found) / NO_EMAIL — fills the pool;</li>
 *   <li><b>sending</b>: ENRICHED → USED — drains the pool (reverts to ENRICHED on failure).</li>
 * </ul>
 * Atomic {@code findAndModify} claims prevent double-processing.
 */
@Service
@RequiredArgsConstructor
public class LeadService {

    private final MongoTemplate mongo;

    /**
     * Claim the next un-enriched company (DEFAULT, has website) for Apollo extraction, biggest
     * companies first ({@code sizeRank} desc) so headcount-heavy leads get enriched before the long
     * tail. Nulls (unknown size) sort last.
     */
    public LeadEntity reserveForExtraction() {
        Query q = new Query(new Criteria().andOperator(
                Criteria.where("status").is("DEFAULT"),
                Criteria.where("website").ne(null).ne("")
        )).with(Sort.by(Sort.Direction.DESC, "sizeRank"));
        Update u = new Update().set("status", "ENRICHING").set("updatedAt", Instant.now());
        return mongo.findAndModify(q, u, FindAndModifyOptions.options().returnNew(true), LeadEntity.class);
    }

    /** Store the extracted email on the company and mark it ready to send. */
    public void markEnriched(String id, String email) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(id)),
                new Update().set("emails", List.of(email)).set("status", "ENRICHED").set("updatedAt", Instant.now()),
                LeadEntity.class);
    }

    /** Apollo found nothing — take the company out of the extraction/send rotation. */
    public void markNoEmail(String id) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(id)),
                new Update().set("status", "NO_EMAIL").set("updatedAt", Instant.now()), LeadEntity.class);
    }

    /** Recovery: release any leads stuck mid-extraction (e.g. after a crash/restart). */
    public long resetStaleEnriching() {
        return mongo.updateMulti(new Query(Criteria.where("status").is("ENRICHING")),
                new Update().set("status", "DEFAULT"), LeadEntity.class).getModifiedCount();
    }

    /**
     * Claim the next enriched company (has an email) to send to, biggest companies first
     * ({@code sizeRank} desc). Skips companies we have already emailed ({@code emailSended=true}) —
     * historical/seeded contacts stay ENRICHED and visible but are never re-contacted.
     */
    public LeadEntity reserveForSending() {
        Query q = new Query(new Criteria().andOperator(
                Criteria.where("status").is("ENRICHED"),
                Criteria.where("emails.0").exists(true),
                Criteria.where("emailSended").ne(true)
        )).with(Sort.by(Sort.Direction.DESC, "sizeRank"));
        Update u = new Update().set("status", "USED").set("updatedAt", Instant.now());
        return mongo.findAndModify(q, u, FindAndModifyOptions.options().returnNew(true), LeadEntity.class);
    }

    /** Put a claimed-for-sending lead back into the pool (send failed / skipped). */
    public void revertToEnriched(String id) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(id)),
                new Update().set("status", "ENRICHED"), LeadEntity.class);
    }

    /** Mark a company as emailed so it is never picked again (belt-and-braces with USED status). */
    public void markContacted(String id) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(id)),
                new Update().set("emailSended", true).set("updatedAt", Instant.now()), LeadEntity.class);
    }

    /** Flag a company whose email hard-bounced — status BOUNCED, so it is excluded from re-targeting. */
    public void markBounced(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        mongo.updateFirst(new Query(Criteria.where("_id").is(id)),
                new Update().set("status", "BOUNCED").set("emailSended", true).set("updatedAt", Instant.now()),
                LeadEntity.class);
    }

    /** Count of companies genuinely ready to send (enriched and not yet contacted). */
    public long enrichedCount() {
        return mongo.count(new Query(new Criteria().andOperator(
                Criteria.where("status").is("ENRICHED"),
                Criteria.where("emailSended").ne(true))), LeadEntity.class);
    }
}
