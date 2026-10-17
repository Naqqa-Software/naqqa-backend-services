package com.naqqa.sso.contract;

import com.naqqa.sso.otp.OtpRecord;
import com.naqqa.sso.otp.OtpStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class OtpStoreContract {
    protected OtpStore store;

    protected abstract OtpStore newStore();

    @BeforeEach
    public void createStore() {
        store = newStore();
    }

    private String challenge(String destination) {
        String id = Race.id("otp");
        store.save(new OtpRecord(id, destination, "login", "mac", Race.now(), Race.now().plusSeconds(300), 0, false));
        return id;
    }

    @Test
    @DisplayName("SSO-T-contract-030 OtpStore.recordAttempt reserves at most maxAttempts attempts under contention")
    public void attemptsCapped() throws Exception {
        String id = challenge(Race.id("dest"));
        assertEquals(5, Race.count(Race.run(Race.THREADS, () -> store.recordAttempt(id, 5).isPresent())));
        assertEquals(5, store.find(id).orElseThrow().attempts());
        assertTrue(store.recordAttempt(id, 5).isEmpty());
        assertTrue(store.recordAttempt(Race.id("missing"), 5).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-contract-031 OtpStore.consume flips once under contention and a consumed challenge takes no attempt")
    public void consumeOnce() throws Exception {
        String id = challenge(Race.id("dest"));
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> store.consume(id))));
        assertTrue(store.find(id).orElseThrow().consumed());
        assertTrue(store.recordAttempt(id, 5).isEmpty());
        assertFalse(store.consume(Race.id("missing")));
    }

    @Test
    @DisplayName("SSO-T-contract-032 OtpStore.invalidateDestination consumes every live challenge of the destination only")
    public void invalidateDestination() {
        String dest = Race.id("dest");
        String a = challenge(dest);
        String b = challenge(dest);
        String other = challenge(Race.id("dest"));
        store.invalidateDestination(dest);
        assertFalse(store.consume(a));
        assertFalse(store.consume(b));
        assertTrue(store.consume(other));
    }

    @Test
    @DisplayName("SSO-T-contract-034 OtpStore.invalidateDestination racing recordAttempt and consume leaves every challenge consumed: no racing writer revives one, a consume flips each at most once")
    public void invalidateRacingAttempts() throws Exception {
        String dest = Race.id("dest");
        List<String> ids = List.of(challenge(dest), challenge(dest), challenge(dest), challenge(dest));
        AtomicInteger n = new AtomicInteger();
        Map<String, AtomicInteger> flips = new ConcurrentHashMap<>();
        Race.run(Race.THREADS, () -> {
            int i = n.getAndIncrement();
            if (i == 0) {
                store.invalidateDestination(dest);
                return null;
            }
            String id = ids.get(i % ids.size());
            for (int k = 0; k < 20; k++) {
                store.recordAttempt(id, 1000);
            }
            if (i % 3 == 0 && store.consume(id)) {
                flips.computeIfAbsent(id, x -> new AtomicInteger()).incrementAndGet();
            }
            return null;
        });
        for (String id : ids) {
            OtpRecord r = store.find(id).orElseThrow();
            assertTrue(r.consumed());
            assertTrue(r.attempts() <= 20 * Race.THREADS);
            assertTrue(store.recordAttempt(id, 1000).isEmpty());
            assertFalse(store.consume(id));
            assertTrue(flips.getOrDefault(id, new AtomicInteger()).get() <= 1);
        }
    }

    @Test
    @DisplayName("SSO-T-contract-033 OtpStore send log counts every recorded send, including parallel ones")
    public void sendLog() throws Exception {
        String dest = Race.id("dest");
        var since = Race.now().minusSeconds(1);
        Race.run(Race.THREADS, () -> {
            store.recordSend(dest, Race.now());
            return null;
        });
        assertEquals(Race.THREADS, store.sendsSince(dest, since));
        assertTrue(store.lastSend(dest).isPresent());
        assertTrue(store.lastSend(Race.id("none")).isEmpty());
    }
}
