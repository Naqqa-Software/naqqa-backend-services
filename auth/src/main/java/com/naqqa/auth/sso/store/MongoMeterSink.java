package com.naqqa.auth.sso.store;

import com.naqqa.sso.billing.MeterSink;
import com.naqqa.sso.billing.Metric;
import com.naqqa.sso.billing.UsageEvent;
import com.naqqa.sso.billing.UsageMeter;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

@Slf4j
public class MongoMeterSink implements MeterSink {

    public static final String COUNTERS = "sso_usage_counters";
    public static final String MAU = "sso_usage_mau";
    static final Duration RETENTION = Duration.ofDays(400);

    private final MongoTemplate template;
    private final Clock clock;
    private final long mauCap;

    public MongoMeterSink(MongoTemplate template, Clock clock, long mauCap) {
        this.template = template;
        this.clock = clock;
        this.mauCap = mauCap;
    }

    @Override
    public void accept(UsageEvent event) {
        if (event == null || event.tenantId() == null || event.metric() == null) {
            return;
        }
        Instant at = event.at() == null ? clock.instant() : event.at();
        String month = month(at);
        try {
            if (event.metric() == Metric.MONTHLY_ACTIVE_USERS) {
                activeUser(event.tenantId(), month, event.subject(), at);
            } else {
                increment(event.tenantId(), month, field(event.metric()), event.amount(), at);
            }
        } catch (DataAccessException e) {
            log.warn("usage metering write failed for metric {}", event.metric());
        }
    }

    public UsageMeter.Snapshot snapshot(String tenantId, long authRequestsThisMinute) {
        String month = month(clock.instant());
        long users = template.count(new Query(Criteria.where("tenantId").is(tenantId).and("month").is(month)), MAU);
        Document counters = template.findById(key(tenantId, month), Document.class, COUNTERS);
        long auth = counters == null ? 0 : MongoSupport.number(counters, field(Metric.AUTH_EVENT));
        long tokens = counters == null ? 0 : MongoSupport.number(counters, field(Metric.TOKEN_ISSUED));
        return new UsageMeter.Snapshot(tenantId, month, users, users >= mauCap, auth, tokens, authRequestsThisMinute);
    }

    private void activeUser(String tenantId, String month, String subject, Instant at) {
        if (subject == null || subject.isEmpty()) {
            return;
        }
        Query q = new Query(Criteria.where("tenantId").is(tenantId).and("month").is(month).and("subject").is(subject));
        Update u = new Update().setOnInsert("firstSeen", MongoSupport.date(at))
                .setOnInsert("purgeAt", MongoSupport.date(at.plus(RETENTION)));
        try {
            template.upsert(q, u, MAU);
        } catch (DuplicateKeyException ignored) {
        }
    }

    private void increment(String tenantId, String month, String field, long amount, Instant at) {
        Query q = new Query(Criteria.where("_id").is(key(tenantId, month)));
        Update u = new Update().inc(field, amount)
                .setOnInsert("tenantId", tenantId)
                .setOnInsert("month", month)
                .setOnInsert("purgeAt", MongoSupport.date(at.plus(RETENTION)));
        for (int i = 0; ; i++) {
            try {
                template.upsert(q, u, COUNTERS);
                return;
            } catch (DuplicateKeyException e) {
                if (i >= 3) {
                    throw e;
                }
            }
        }
    }

    static String field(Metric metric) {
        return switch (metric) {
            case AUTH_EVENT -> "authEvents";
            case TOKEN_ISSUED -> "tokensIssued";
            default -> metric.name().toLowerCase();
        };
    }

    static String key(String tenantId, String month) {
        return tenantId + "|" + month;
    }

    static String month(Instant at) {
        return YearMonth.from(at.atZone(ZoneOffset.UTC)).toString();
    }
}
