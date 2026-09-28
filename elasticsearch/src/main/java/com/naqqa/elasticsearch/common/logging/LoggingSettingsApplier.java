package com.naqqa.elasticsearch.common.logging;

import com.naqqa.elasticsearch.cluster.service.ClusterChangedEvent;
import com.naqqa.elasticsearch.cluster.service.ClusterStateApplier;

public final class LoggingSettingsApplier implements ClusterStateApplier {

    @Override
    public void applyClusterState(ClusterChangedEvent event) {
        if (!event.metadataChanged()) {
            return;
        }
        LogConfigurator.applySettings(event.state().getMetadata().settings().getAsMap());
    }
}
