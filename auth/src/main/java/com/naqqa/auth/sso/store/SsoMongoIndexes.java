package com.naqqa.auth.sso.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

import java.time.Duration;

public final class SsoMongoIndexes {

    private static final Logger log = LoggerFactory.getLogger(SsoMongoIndexes.class);
    public static final String USER_EMAIL_INDEX = UserEmailIndex.NAME;

    private SsoMongoIndexes() {
    }

    public static void ensure(MongoTemplate t) {
        ttl(t, MongoChallengeStore.COLLECTION, "expiresAt", Duration.ZERO);
        ttl(t, MongoTotpStepStore.COLLECTION, "expiresAt", Duration.ZERO);
        ttl(t, MongoOtpStore.COLLECTION, "expiresAt", Duration.ofHours(1));
        ttl(t, MongoOtpStore.SENDS, "expiresAt", Duration.ZERO);
        ttl(t, MongoMagicLinkStore.COLLECTION, "expiresAt", Duration.ofHours(1));
        ensureCore(t);
        ttl(t, MongoRateLimitStore.COLLECTION, "expiresAt", Duration.ZERO);
        ttl(t, MongoPushApprovalStore.COLLECTION, "purgeAt", Duration.ZERO);

        t.indexOps(MongoCredentialStore.COLLECTION).ensureIndex(new Index().on("userId", Sort.Direction.ASC).named("userId"));
        t.indexOps(MongoCredentialStore.COLLECTION).ensureIndex(new Index().on("userHandle", Sort.Direction.ASC).named("userHandle"));
        t.indexOps(MongoOtpStore.COLLECTION).ensureIndex(new Index().on("destinationKey", Sort.Direction.ASC).named("destinationKey"));
        t.indexOps(MongoOtpStore.SENDS).ensureIndex(new Index().on("destinationKey", Sort.Direction.ASC).on("at", Sort.Direction.DESC).named("destination_at"));
        t.indexOps(MongoMagicLinkStore.COLLECTION).ensureIndex(new Index().on("userId", Sort.Direction.ASC).named("userId"));
        ttl(t, MongoSessionStore.COLLECTION, "purgeAt", Duration.ZERO);
        ttl(t, "sso_session_meta", "purgeAt", Duration.ZERO);
        ttl(t, "sso_risk_profiles", "purgeAt", Duration.ZERO);
        t.indexOps("sso_session_meta").ensureIndex(new Index().on("userId", Sort.Direction.ASC).named("userId"));
        t.indexOps(MongoSessionStore.COLLECTION).ensureIndex(new Index().on("userId", Sort.Direction.ASC).named("userId"));
        t.indexOps(MongoSessionStore.COLLECTION).ensureIndex(new Index().on("tenantId", Sort.Direction.ASC).named("tenantId"));
        t.indexOps(MongoSessionStore.COLLECTION).ensureIndex(new Index().on("clientIds", Sort.Direction.ASC).named("clientIds"));
        t.indexOps(MongoAuditStore.COLLECTION).ensureIndex(new Index().on("tenantId", Sort.Direction.ASC).on("_id", Sort.Direction.ASC).named("tenant_seq"));
        t.indexOps(MongoAuditStore.COLLECTION).ensureIndex(new Index().on("action", Sort.Direction.ASC).named("action"));
        t.indexOps(MongoApiTokenStore.COLLECTION).ensureIndex(new Index().on("tenantId", Sort.Direction.ASC).named("tenantId"));
        t.indexOps(MongoApiTokenStore.COLLECTION).ensureIndex(new Index().on("hash", Sort.Direction.ASC).unique().sparse().named("hash_unique"));
        ttl(t, MongoMeterSink.MAU, "purgeAt", Duration.ZERO);
        ttl(t, MongoMeterSink.COUNTERS, "purgeAt", Duration.ZERO);
        t.indexOps(MongoMeterSink.MAU).ensureIndex(new Index().on("tenantId", Sort.Direction.ASC).on("month", Sort.Direction.ASC)
                .on("subject", Sort.Direction.ASC).unique().named("tenant_month_subject"));
    }

    public static UserEmailIndex.Report ensureUserEmail(MongoTemplate t) {
        try {
            UserEmailIndex.Report report = UserEmailIndex.migrate(t);
            if (!report.indexPresent()) {
                log.warn("users: {} email addresses are shared by {} accounts that differ only in letter case; index {} not created, "
                                + "sign-in by email resolves to the oldest account of each group, the newer accounts are listed at "
                                + "GET /api/sso/admin/users/email-conflicts until resolved",
                        report.groups(), report.accounts(), USER_EMAIL_INDEX);
            }
            return report;
        } catch (RuntimeException e) {
            log.error("users: case-insensitive email index check failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    public static void ensureCore(MongoTemplate t) {
        ensureUserEmail(t);
        ttl(t, MongoRefreshStore.COLLECTION, "familyExpiry", Duration.ofHours(1));
        ttl(t, MongoRefreshStore.REVOKED, "expiresAt", Duration.ofHours(1));
        t.indexOps(MongoRefreshStore.COLLECTION).ensureIndex(new Index().on("familyId", Sort.Direction.ASC).named("familyId"));
        t.indexOps(MongoRefreshStore.COLLECTION).ensureIndex(new Index().on("userId", Sort.Direction.ASC).named("userId"));
        t.indexOps(MongoRefreshStore.COLLECTION).ensureIndex(new Index().on("clientId", Sort.Direction.ASC).named("clientId"));
    }

    private static void ttl(MongoTemplate t, String collection, String field, Duration after) {
        t.indexOps(collection).ensureIndex(new Index().on(field, Sort.Direction.ASC).expire(after).named("ttl_" + field));
    }
}
