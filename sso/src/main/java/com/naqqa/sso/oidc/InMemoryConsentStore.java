package com.naqqa.sso.oidc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryConsentStore implements ConsentStore {
    private final Map<String, ConsentRecord> records = new ConcurrentHashMap<>();

    private static String key(String tenantId, String userId, String clientId) {
        return tenantId + "\u0000" + userId + "\u0000" + clientId;
    }

    @Override
    public Optional<ConsentRecord> find(String tenantId, String userId, String clientId) {
        return Optional.ofNullable(records.get(key(tenantId, userId, clientId)));
    }

    @Override
    public void save(ConsentRecord record) {
        records.put(key(record.tenantId(), record.userId(), record.clientId()), record);
    }

    @Override
    public void grant(String tenantId, String userId, String clientId, Set<String> scopes, Instant at) {
        records.compute(key(tenantId, userId, clientId), (k, current) -> {
            Set<String> all = new LinkedHashSet<>(scopes);
            if (current != null) {
                all.addAll(current.scopes());
            }
            return new ConsentRecord(tenantId, userId, clientId, all, at);
        });
    }

    @Override
    public boolean revoke(String tenantId, String userId, String clientId) {
        return records.remove(key(tenantId, userId, clientId)) != null;
    }

    @Override
    public List<ConsentRecord> listByUser(String tenantId, String userId) {
        List<ConsentRecord> out = new ArrayList<>();
        for (ConsentRecord r : records.values()) {
            if (r.tenantId().equals(tenantId) && r.userId().equals(userId)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparing(ConsentRecord::clientId));
        return out;
    }
}
