package com.naqqa.elasticsearch.indices.tier;

import com.naqqa.elasticsearch.cluster.routing.allocation.decider.DataTierAllocationDecider;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DataTierAllocation {

    public static final String TIER_PREFERENCE_SETTING = DataTierAllocationDecider.TIER_PREFERENCE_KEY;

    private DataTierAllocation() {
    }

    public static String preferenceChain(DataTier tier) {
        return switch (tier) {
            case DATA_CONTENT -> "data_content";
            case DATA_HOT -> "data_hot,data_content";
            case DATA_WARM -> "data_warm,data_hot,data_content";
            case DATA_COLD -> "data_cold,data_warm,data_hot,data_content";
            case DATA_FROZEN -> "data_frozen,data_cold,data_warm,data_hot,data_content";
        };
    }

    public static String resolvePreferredTier(String preferenceCsv, Set<String> availableTierRoleNames) {
        if (preferenceCsv == null || preferenceCsv.isBlank()) {
            return null;
        }
        for (String tier : preferenceCsv.split(",")) {
            String trimmed = tier.trim().toLowerCase(Locale.ROOT);
            if (availableTierRoleNames.contains(trimmed)) {
                return trimmed;
            }
        }
        return null;
    }

    public static Map<String, String> preferenceSettings(DataTier tier) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(TIER_PREFERENCE_SETTING, preferenceChain(tier));
        return settings;
    }
}
