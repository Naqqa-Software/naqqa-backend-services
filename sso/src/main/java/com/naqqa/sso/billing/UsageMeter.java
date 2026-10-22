package com.naqqa.sso.billing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class UsageMeter {

    public record Snapshot(String tenantId, String month, long monthlyActiveUsers, boolean mauSaturated,
                           long authEvents, long tokensIssued, long authRequestsThisMinute) {
    }

    private final Clock clock;
    private final byte[] hmacKey;
    private final MeterSink sink;
    private final int mauCap;
    private final Map<String, Set<String>> mau = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> minuteCounters = new ConcurrentHashMap<>();

    public UsageMeter(Clock clock, byte[] hmacKey, MeterSink sink, int mauCap) {
        if (hmacKey == null || hmacKey.length < 16) {
            throw new IllegalArgumentException("hmacKey must be at least 16 bytes");
        }
        if (mauCap < 1) {
            throw new IllegalArgumentException("mauCap");
        }
        this.clock = clock;
        this.hmacKey = hmacKey.clone();
        this.sink = sink;
        this.mauCap = mauCap;
    }

    public boolean recordActiveUser(String tenantId, String userId) {
        Instant now = clock.instant();
        String month = month(now);
        evictOldMonths(month);
        String token = hmac(tenantId, userId);
        Set<String> set = mau.computeIfAbsent(tenantId + "|" + month, k -> ConcurrentHashMap.newKeySet());
        boolean added;
        synchronized (set) {
            added = set.size() < mauCap && set.add(token);
        }
        if (added) {
            sink.accept(new UsageEvent(now, tenantId, Metric.MONTHLY_ACTIVE_USERS, 1, token, Map.of("month", month)));
        }
        return added;
    }

    public long recordAuthEvent(String tenantId, String kind) {
        Instant now = clock.instant();
        String month = month(now);
        evictOldMonths(month);
        long n = counter("auth", tenantId, month).incrementAndGet();
        sink.accept(new UsageEvent(now, tenantId, Metric.AUTH_EVENT, 1, null, kind == null ? Map.of() : Map.of("kind", kind)));
        return n;
    }

    public long recordTokenIssued(String tenantId, String tokenType) {
        Instant now = clock.instant();
        String month = month(now);
        evictOldMonths(month);
        long n = counter("token", tenantId, month).incrementAndGet();
        sink.accept(new UsageEvent(now, tenantId, Metric.TOKEN_ISSUED, 1, null, tokenType == null ? Map.of() : Map.of("type", tokenType)));
        return n;
    }

    public long recordAuthRequest(String tenantId) {
        long minute = clock.instant().getEpochSecond() / 60;
        minuteCounters.keySet().removeIf(k -> !k.endsWith("|" + minute));
        return minuteCounters.computeIfAbsent(tenantId + "|" + minute, k -> new AtomicLong()).incrementAndGet();
    }

    public Snapshot snapshot(String tenantId) {
        Instant now = clock.instant();
        String month = month(now);
        long minute = now.getEpochSecond() / 60;
        Set<String> set = mau.get(tenantId + "|" + month);
        long users = set == null ? 0 : set.size();
        AtomicLong reqs = minuteCounters.get(tenantId + "|" + minute);
        return new Snapshot(tenantId, month, users, users >= mauCap,
                counter("auth", tenantId, month).get(), counter("token", tenantId, month).get(), reqs == null ? 0 : reqs.get());
    }

    private AtomicLong counter(String kind, String tenantId, String month) {
        return counters.computeIfAbsent(kind + "|" + tenantId + "|" + month, k -> new AtomicLong());
    }

    private void evictOldMonths(String current) {
        mau.keySet().removeIf(k -> !k.endsWith("|" + current));
        counters.keySet().removeIf(k -> !k.endsWith("|" + current));
    }

    private static String month(Instant i) {
        return YearMonth.from(i.atZone(ZoneOffset.UTC)).toString();
    }

    private String hmac(String tenantId, String userId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            mac.update(tenantId.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return HexFormat.of().formatHex(mac.doFinal(userId.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
