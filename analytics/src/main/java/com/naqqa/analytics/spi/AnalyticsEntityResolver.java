package com.naqqa.analytics.spi;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public interface AnalyticsEntityResolver {

    AnalyticsEntityResolver NONE = new AnalyticsEntityResolver() {
    };

    default EntityInfo resolve(String entityType, String entityId) {
        return null;
    }

    default Map<String, EntityInfo> resolveAll(String entityType, Collection<String> entityIds) {
        Map<String, EntityInfo> out = new LinkedHashMap<>();
        if (entityIds == null) {
            return out;
        }
        for (String id : entityIds) {
            EntityInfo info = resolve(entityType, id);
            if (info != null) {
                out.put(id, info);
            }
        }
        return out;
    }

    record EntityInfo(String companyId, String categoryId, String title, boolean test) {
    }
}
