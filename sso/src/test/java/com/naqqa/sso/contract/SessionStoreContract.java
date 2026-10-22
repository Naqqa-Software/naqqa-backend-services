package com.naqqa.sso.contract;

import com.naqqa.sso.session.Session;
import com.naqqa.sso.session.SessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class SessionStoreContract {
    protected SessionStore store;

    protected abstract SessionStore newStore();

    @BeforeEach
    public void createStore() {
        store = newStore();
    }

    private static Session session(String id, String user, Set<String> clients) {
        Instant now = Race.now();
        return new Session(id, user, "t", clients, now, now, null, "127.0.0.1", null, Set.of("pwd"), null, now);
    }

    @Test
    @DisplayName("SSO-T-contract-010 SessionStore.replace never upserts and never resurrects a deleted session under contention")
    public void replaceNeverResurrects() throws Exception {
        String user = Race.id("u");
        Session ghost = session(Race.id("s"), user, Set.of("a"));
        assertFalse(store.replace(ghost));
        assertTrue(store.find(ghost.idHash()).isEmpty());
        for (int round = 0; round < 5; round++) {
            Session s = session(Race.id("s"), user, Set.of("a"));
            store.save(s);
            Race.run(Race.THREADS, () -> {
                if (Thread.currentThread().getId() % 2 == 0) {
                    store.delete(s.idHash());
                } else {
                    store.replace(session(s.idHash(), user, Set.of("a", "b")));
                }
                return null;
            });
            store.delete(s.idHash());
            assertEquals(0, Race.count(Race.run(Race.THREADS, () -> store.replace(session(s.idHash(), user, Set.of("late"))))));
            assertTrue(store.find(s.idHash()).isEmpty());
        }
    }

    @Test
    @DisplayName("SSO-T-contract-011 SessionStore.replace overwrites a live session and deleteByUser removes every session of the user only")
    public void replaceAndDeleteByUser() {
        String user = Race.id("u");
        String other = Race.id("u");
        Session s = session(Race.id("s"), user, Set.of("a"));
        store.save(s);
        store.save(session(Race.id("s"), user, Set.of("a")));
        store.save(session(Race.id("s"), other, Set.of("a")));
        assertTrue(store.replace(session(s.idHash(), user, Set.of("a", "b"))));
        assertEquals(Set.of("a", "b"), store.find(s.idHash()).orElseThrow().clientIds());
        assertEquals(2, store.listByUser(user).size());
        assertEquals(2, store.deleteByUser(user));
        assertTrue(store.listByUser(user).isEmpty());
        assertEquals(1, store.listByUser(other).size());
    }
}
