package com.naqqa.sso.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySessionStore implements SessionStore {
    private final ConcurrentHashMap<String, Session> map = new ConcurrentHashMap<>();

    @Override
    public void save(Session session) {
        map.put(session.idHash(), session);
    }

    @Override
    public boolean replace(Session session) {
        return map.computeIfPresent(session.idHash(), (k, v) -> session) != null;
    }

    @Override
    public Optional<Session> find(String idHash) {
        return Optional.ofNullable(map.get(idHash));
    }

    @Override
    public void delete(String idHash) {
        map.remove(idHash);
    }

    @Override
    public List<Session> listByUser(String userId) {
        List<Session> out = new ArrayList<>();
        for (Session s : map.values()) {
            if (s.userId().equals(userId)) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparing(Session::created).thenComparing(Session::idHash));
        return out;
    }

    @Override
    public int deleteByUser(String userId) {
        int[] n = {0};
        map.values().removeIf(s -> {
            boolean match = s.userId().equals(userId);
            if (match) {
                n[0]++;
            }
            return match;
        });
        return n[0];
    }
}
