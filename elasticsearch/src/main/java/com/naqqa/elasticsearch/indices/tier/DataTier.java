package com.naqqa.elasticsearch.indices.tier;

import java.util.Locale;

public enum DataTier {
    DATA_CONTENT("data_content"),
    DATA_HOT("data_hot"),
    DATA_WARM("data_warm"),
    DATA_COLD("data_cold"),
    DATA_FROZEN("data_frozen");

    private final String roleName;

    DataTier(String roleName) {
        this.roleName = roleName;
    }

    public String roleName() {
        return roleName;
    }

    public static DataTier fromRoleName(String roleName) {
        for (DataTier tier : values()) {
            if (tier.roleName.equals(roleName.toLowerCase(Locale.ROOT))) {
                return tier;
            }
        }
        throw new IllegalArgumentException("unknown data tier role [" + roleName + "]");
    }
}
