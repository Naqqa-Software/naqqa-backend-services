package com.naqqa.sso.otp;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

public final class PushApproval {
    public enum Status {
        PENDING, APPROVED, DENIED, EXPIRED, CONSUMED
    }

    public record Challenge(String id, String displayNumber, List<String> choices, Instant expiresAt) {
    }

    private final Clock clock;
    private final Transport transport;
    private final Duration ttl;
    private final int maxMismatches;
    private final PushApprovalStore store;

    public PushApproval(Clock clock, Transport transport, PushApprovalStore store, Duration ttl, int maxMismatches) {
        this.clock = clock;
        this.store = store;
        this.transport = transport;
        this.ttl = ttl;
        this.maxMismatches = maxMismatches;
    }

    public PushApproval(Clock clock, Transport transport, Duration ttl, int maxMismatches) {
        this(clock, transport, new InMemoryPushApprovalStore(), ttl, maxMismatches);
    }

    public PushApproval(Clock clock, Transport transport, PushApprovalStore store) {
        this(clock, transport, store, Duration.ofMinutes(2), 2);
    }

    public PushApproval(Clock clock, Transport transport) {
        this(clock, transport, new InMemoryPushApprovalStore(), Duration.ofMinutes(2), 2);
    }

    private static String twoDigits() {
        int limit = 256 - (256 % 90);
        while (true) {
            for (byte b : Bytes.random(8)) {
                int v = b & 0xFF;
                if (v < limit) {
                    return Integer.toString(10 + v % 90);
                }
            }
        }
    }

    public Challenge create(String userId, String deviceDestination) {
        Set<String> numbers = new LinkedHashSet<>();
        String real = twoDigits();
        numbers.add(real);
        while (numbers.size() < 3) {
            numbers.add(twoDigits());
        }
        List<String> choices = new ArrayList<>(numbers);
        Collections.shuffle(choices, new java.security.SecureRandom());
        String id = Bytes.randomToken(18);
        Instant expires = clock.instant().plus(ttl);
        store.create(new PushApprovalRecord(id, userId, real, expires, Status.PENDING, 0));
        transport.send(Channel.PUSH, deviceDestination, "push.approve", Map.of("challengeId", id, "choices", String.join(",", choices)));
        return new Challenge(id, real, List.copyOf(choices), expires);
    }

    public Status respond(String id, String userId, String selectedNumber, boolean approve) {
        PushApprovalRecord r = store.find(id).orElse(null);
        if (r == null || !r.userId().equals(userId)) {
            throw new SsoException("push_invalid");
        }
        if (r.status() == Status.PENDING && !clock.instant().isBefore(r.expiresAt())) {
            store.transition(id, Status.PENDING, Status.EXPIRED);
            return current(id);
        }
        if (r.status() != Status.PENDING) {
            return r.status();
        }
        if (!approve) {
            store.transition(id, Status.PENDING, Status.DENIED);
            return current(id);
        }
        if (!Bytes.constantTimeEquals(r.number(), selectedNumber == null ? "" : selectedNumber)) {
            OptionalInt count = store.addMismatch(id);
            if (count.isPresent() && count.getAsInt() >= maxMismatches) {
                store.transition(id, Status.PENDING, Status.DENIED);
            }
            return current(id);
        }
        store.transition(id, Status.PENDING, Status.APPROVED);
        return current(id);
    }

    private Status current(String id) {
        return store.find(id).map(PushApprovalRecord::status).orElseThrow(() -> new SsoException("push_invalid"));
    }

    public Status status(String id) {
        PushApprovalRecord r = store.find(id).orElseThrow(() -> new SsoException("push_invalid"));
        if ((r.status() == Status.PENDING || r.status() == Status.APPROVED) && !clock.instant().isBefore(r.expiresAt())) {
            store.transition(id, r.status(), Status.EXPIRED);
            return current(id);
        }
        return r.status();
    }

    public boolean consume(String id, String userId) {
        PushApprovalRecord r = store.find(id).orElse(null);
        if (r == null || !r.userId().equals(userId) || status(id) != Status.APPROVED) {
            return false;
        }
        return store.transition(id, Status.APPROVED, Status.CONSUMED);
    }
}
