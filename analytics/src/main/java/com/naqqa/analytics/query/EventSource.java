package com.naqqa.analytics.query;

import com.naqqa.analytics.model.AnalyticsEvent;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface EventSource {

    List<AnalyticsEvent> events(AnalyticsQuery query, Set<String> include, Set<String> exclude);

    List<Group> group(AnalyticsQuery query, String name, List<String> keys);

    List<AnalyticsEvent> recent(long sinceMs, Set<String> companyIds, int limit);

    List<DimensionCount> dimension(AnalyticsQuery query, String field, String prefix, int limit);

    record DimensionCount(String value, long count) {
    }

    record Group(Map<String, String> key, long count, long uniques, long positionSum, long positionCount) {

        public String get(String field) {
            return key.get(field);
        }
    }
}
