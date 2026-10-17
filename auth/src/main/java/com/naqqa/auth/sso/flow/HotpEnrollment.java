package com.naqqa.auth.sso.flow;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.store.MongoSupport;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.mfa.Base32;
import com.naqqa.sso.mfa.Hotp;
import com.naqqa.sso.mfa.OtpAlgorithm;
import com.naqqa.sso.mfa.OtpAuthUri;
import com.naqqa.sso.mfa.SecretSealer;
import com.naqqa.sso.util.Bytes;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

public class HotpEnrollment {

    public static final String COLLECTION = "sso_hotp_credentials";

    public record Begin(String secretBase32, String otpauthUri, long counter) {
    }

    private final Clock clock;
    private final SecretSealer sealer;
    private final MongoTemplate template;
    private final OtpAlgorithm algorithm;
    private final int digits;
    private final int lookAhead;
    private final int resyncWindow;
    private final java.time.Duration pendingTtl;

    public HotpEnrollment(Clock clock, SecretSealer sealer, MongoTemplate template, SsoProperties.Hotp config) {
        this.clock = clock;
        this.sealer = sealer;
        this.template = template;
        this.algorithm = OtpAlgorithm.valueOf(config.getAlgorithm().toUpperCase(Locale.ROOT));
        this.digits = config.getDigits();
        this.lookAhead = config.getLookAhead();
        this.resyncWindow = config.getResyncWindow();
        this.pendingTtl = config.getPendingTtl();
        if (digits < 6 || digits > 8 || lookAhead < 1 || lookAhead > 100 || resyncWindow < lookAhead || resyncWindow > 1000) {
            throw new SsoException("hotp_config_invalid");
        }
    }

    private static byte[] aad(String userId) {
        return Bytes.utf8("hotp:" + userId);
    }

    private Document find(String userId) {
        return template.findById(userId, Document.class, COLLECTION);
    }

    public synchronized Begin begin(String userId, String issuer, String account) {
        Document existing = find(userId);
        if (existing != null && MongoSupport.flag(existing, "confirmed")) {
            throw new SsoException("hotp_already_enrolled");
        }
        byte[] secret = Bytes.random(20);
        byte[] sealed = sealer.seal(secret, aad(userId));
        Query pending = new Query(Criteria.where("_id").is(userId).and("confirmed").ne(true));
        Update update = new Update().set("sealedSecret", sealed).set("counter", 0L).set("confirmed", false)
                .set("createdAt", MongoSupport.date(clock.instant())).unset("confirmedAt");
        try {
            template.upsert(pending, update, COLLECTION);
        } catch (DuplicateKeyException e) {
            throw new SsoException("hotp_already_enrolled");
        }
        Begin begin = new Begin(Base32.encode(secret), OtpAuthUri.hotp(issuer, account, secret, algorithm, digits, 0), 0);
        Bytes.wipe(secret);
        return begin;
    }

    private boolean wellFormed(String code) {
        return code != null && code.length() == digits && code.chars().allMatch(ch -> ch >= '0' && ch <= '9');
    }

    private long match(byte[] secret, String code, long from, long span) {
        long matched = -1;
        for (long c = from; c <= from + span; c++) {
            if (Bytes.constantTimeEquals(Hotp.generate(secret, c, digits, algorithm), code) && matched < 0) {
                matched = c;
            }
        }
        return matched;
    }

    public synchronized boolean confirm(String userId, String code) {
        Document d = find(userId);
        if (d == null) {
            throw new SsoException("hotp_not_enrolled");
        }
        if (MongoSupport.flag(d, "confirmed")) {
            throw new SsoException("hotp_already_enrolled");
        }
        Instant now = clock.instant();
        if (!now.isBefore(MongoSupport.instant(d.getDate("createdAt")).plus(pendingTtl))) {
            remove(userId);
            throw new SsoException("hotp_enrollment_expired");
        }
        if (!wellFormed(code)) {
            return false;
        }
        byte[] sealed = MongoSupport.bytes(d.get("sealedSecret"));
        byte[] secret = sealer.open(sealed, aad(userId));
        try {
            long matched = match(secret, code, 0, lookAhead);
            if (matched < 0) {
                return false;
            }
            Query pending = new Query(Criteria.where("_id").is(userId).and("confirmed").ne(true).and("sealedSecret").is(sealed));
            return template.updateFirst(pending, new Update().set("confirmed", true).set("counter", matched + 1)
                    .set("confirmedAt", MongoSupport.date(now)), COLLECTION).getModifiedCount() == 1;
        } finally {
            Bytes.wipe(secret);
        }
    }

    public boolean verify(String userId, String code) {
        Document d = find(userId);
        if (d == null || !MongoSupport.flag(d, "confirmed") || !wellFormed(code)) {
            return false;
        }
        byte[] sealed = MongoSupport.bytes(d.get("sealedSecret"));
        byte[] secret = sealer.open(sealed, aad(userId));
        try {
            long counter = MongoSupport.number(d, "counter");
            long matched = match(secret, code, counter, lookAhead);
            return matched >= 0 && advance(userId, sealed, matched, matched + 1);
        } finally {
            Bytes.wipe(secret);
        }
    }

    public boolean resync(String userId, String code, String nextCode) {
        Document d = find(userId);
        if (d == null || !MongoSupport.flag(d, "confirmed") || !wellFormed(code) || !wellFormed(nextCode) || code.equals(nextCode)) {
            return false;
        }
        byte[] sealed = MongoSupport.bytes(d.get("sealedSecret"));
        byte[] secret = sealer.open(sealed, aad(userId));
        try {
            long counter = MongoSupport.number(d, "counter");
            long matched = -1;
            for (long c = counter; c <= counter + resyncWindow; c++) {
                boolean first = Bytes.constantTimeEquals(Hotp.generate(secret, c, digits, algorithm), code);
                boolean second = Bytes.constantTimeEquals(Hotp.generate(secret, c + 1, digits, algorithm), nextCode);
                if (first && second && matched < 0) {
                    matched = c;
                }
            }
            return matched >= 0 && advance(userId, sealed, matched, matched + 2);
        } finally {
            Bytes.wipe(secret);
        }
    }

    private boolean advance(String userId, byte[] sealed, long usedCounter, long next) {
        Query q = new Query(Criteria.where("_id").is(userId).and("confirmed").is(true).and("sealedSecret").is(sealed)
                .and("counter").lte(usedCounter));
        return template.updateFirst(q, new Update().set("counter", next), COLLECTION).getModifiedCount() == 1;
    }

    public boolean isActive(String userId) {
        Document d = find(userId);
        return d != null && MongoSupport.flag(d, "confirmed");
    }

    public synchronized void remove(String userId) {
        template.remove(new Query(Criteria.where("_id").is(userId)), COLLECTION);
    }

    public int digits() {
        return digits;
    }

    public java.time.Duration pendingTtl() {
        return pendingTtl;
    }
}
