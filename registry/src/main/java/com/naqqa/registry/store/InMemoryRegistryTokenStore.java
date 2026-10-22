package com.naqqa.registry.store;

import com.naqqa.registry.domain.RegistryToken;
import com.naqqa.registry.spi.RegistryTokenStore;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryRegistryTokenStore implements RegistryTokenStore {

    private final Map<String, RegistryToken> tokens = new ConcurrentHashMap<>();

    @Override
    public void save(RegistryToken token) {
        tokens.put(token.id(), token);
    }

    @Override
    public Optional<RegistryToken> findById(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(tokens.get(id));
    }

    @Override
    public List<RegistryToken> list(String customerId) {
        return tokens.values().stream()
                .filter(t -> customerId == null || customerId.equals(t.customerId()))
                .sorted(Comparator.comparing(RegistryToken::createdAt).reversed().thenComparing(RegistryToken::id))
                .toList();
    }

    @Override
    public void touch(String id, Instant at) {
        tokens.computeIfPresent(id, (key, token) -> token.withLastUsedAt(at));
    }
}
