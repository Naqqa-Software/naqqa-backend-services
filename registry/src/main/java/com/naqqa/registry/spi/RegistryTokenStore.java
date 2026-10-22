package com.naqqa.registry.spi;

import com.naqqa.registry.domain.RegistryToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RegistryTokenStore {

    void save(RegistryToken token);

    Optional<RegistryToken> findById(String id);

    List<RegistryToken> list(String customerId);

    void touch(String id, Instant at);
}
