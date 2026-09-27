package com.naqqa.elasticsearch.cluster.node;

import java.util.Locale;

public enum DiscoveryNodeRole {

    MASTER,
    DATA,
    DATA_CONTENT,
    DATA_HOT,
    DATA_WARM,
    DATA_COLD,
    DATA_FROZEN,
    INGEST,
    VOTING_ONLY;

    public String roleName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isDataRole() {
        return this == DATA || this == DATA_CONTENT || this == DATA_HOT || this == DATA_WARM
            || this == DATA_COLD || this == DATA_FROZEN;
    }

    public static DiscoveryNodeRole fromRoleName(String name) {
        return valueOf(name.toUpperCase(Locale.ROOT));
    }
}
