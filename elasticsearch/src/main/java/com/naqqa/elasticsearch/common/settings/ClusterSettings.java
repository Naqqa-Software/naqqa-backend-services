package com.naqqa.elasticsearch.common.settings;

import java.util.Set;

public final class ClusterSettings extends AbstractScopedSettings {

    public ClusterSettings(Settings settings, Set<Setting<?>> settingsSet) {
        super(settings, settingsSet, Setting.Property.CLUSTER_SCOPE);
    }
}
