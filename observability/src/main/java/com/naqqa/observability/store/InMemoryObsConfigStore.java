package com.naqqa.observability.store;

import com.naqqa.observability.spi.ObsConfigStore;
import com.naqqa.observability.telemetry.ObsProjectConfig;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryObsConfigStore implements ObsConfigStore {

    private final Map<String, ObsProjectConfig> configs = new ConcurrentHashMap<>();

    @Override
    public Optional<ObsProjectConfig> find(String projectId) {
        return projectId == null ? Optional.empty() : Optional.ofNullable(configs.get(projectId));
    }

    @Override
    public Optional<ObsProjectConfig> findByTokenId(String tokenId) {
        if (tokenId == null) {
            return Optional.empty();
        }
        for (ObsProjectConfig config : configs.values()) {
            if ((config.ingest() != null && tokenId.equals(config.ingest().id()))
                    || (config.management() != null && tokenId.equals(config.management().id()))) {
                return Optional.of(config);
            }
        }
        return Optional.empty();
    }

    @Override
    public void save(ObsProjectConfig config) {
        configs.put(config.projectId(), config);
    }

    @Override
    public Collection<ObsProjectConfig> all() {
        return List.copyOf(configs.values());
    }
}
