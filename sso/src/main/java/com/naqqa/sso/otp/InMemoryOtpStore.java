package com.naqqa.sso.otp;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryOtpStore implements OtpStore {
    private final Map<String, OtpRecord> records = new ConcurrentHashMap<>();
    private final Map<String, List<Instant>> sends = new ConcurrentHashMap<>();

    @Override
    public void save(OtpRecord record) {
        records.put(record.id(), record);
    }

    @Override
    public Optional<OtpRecord> find(String id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public Optional<OtpRecord> recordAttempt(String id, int maxAttempts) {
        OtpRecord[] out = new OtpRecord[1];
        records.computeIfPresent(id, (k, v) -> {
            if (v.consumed() || v.attempts() >= maxAttempts) {
                return v;
            }
            out[0] = v.withAttempts(v.attempts() + 1);
            return out[0];
        });
        return Optional.ofNullable(out[0]);
    }

    @Override
    public boolean consume(String id) {
        boolean[] flipped = new boolean[1];
        records.computeIfPresent(id, (k, v) -> {
            if (v.consumed()) {
                return v;
            }
            flipped[0] = true;
            return v.withConsumed();
        });
        return flipped[0];
    }

    @Override
    public void recordSend(String destinationKey, Instant at) {
        sends.computeIfAbsent(destinationKey, k -> new CopyOnWriteArrayList<>()).add(at);
    }

    @Override
    public Optional<Instant> lastSend(String destinationKey) {
        List<Instant> l = sends.get(destinationKey);
        if (l == null || l.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(l.get(l.size() - 1));
    }

    @Override
    public int sendsSince(String destinationKey, Instant since) {
        List<Instant> l = sends.get(destinationKey);
        if (l == null) {
            return 0;
        }
        int n = 0;
        for (Instant i : l) {
            if (!i.isBefore(since)) {
                n++;
            }
        }
        return n;
    }

    @Override
    public void invalidateDestination(String destinationKey) {
        records.replaceAll((k, v) -> v.destinationKey().equals(destinationKey) ? v.withConsumed() : v);
    }
}
