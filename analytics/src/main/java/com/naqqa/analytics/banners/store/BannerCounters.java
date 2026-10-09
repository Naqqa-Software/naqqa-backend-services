package com.naqqa.analytics.banners.store;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public interface BannerCounters {

    int servedToday(String vid, String campaignId, LocalDate day);

    void recordServe(String vid, String campaignId, LocalDate day);

    boolean seen(String vid, String campaignId);

    boolean firstClick(String key, Duration window);

    default Map<String, Long> rotation(String slot, LocalDate day) {
        return Map.of();
    }

    default void recordRotation(String slot, LocalDate day, Map<String, Long> steps) {
    }

    default void recordRotation(String slot, LocalDate day, String... keys) {
        Map<String, Long> steps = new LinkedHashMap<>();
        for (String k : keys) {
            if (k != null) {
                steps.merge(k, 1L, Long::sum);
            }
        }
        recordRotation(slot, day, steps);
    }
}
