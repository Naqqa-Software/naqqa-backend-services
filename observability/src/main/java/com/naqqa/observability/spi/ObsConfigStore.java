package com.naqqa.observability.spi;

import com.naqqa.observability.telemetry.ObsProjectConfig;

import java.util.Collection;
import java.util.Optional;

public interface ObsConfigStore {

    Optional<ObsProjectConfig> find(String projectId);

    Optional<ObsProjectConfig> findByTokenId(String tokenId);

    void save(ObsProjectConfig config);

    Collection<ObsProjectConfig> all();
}
