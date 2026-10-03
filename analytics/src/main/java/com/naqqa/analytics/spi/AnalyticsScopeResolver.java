package com.naqqa.analytics.spi;

import org.springframework.security.core.Authentication;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

public interface AnalyticsScopeResolver {

    AnalyticsScopeResolver NONE = new AnalyticsScopeResolver() {
    };

    default Set<String> companyIds(Authentication authentication) {
        return Set.of();
    }

    default Set<String> companyIdsForUser(String userId) {
        return Set.of();
    }

    default Map<String, String> companyTitles(Collection<String> companyIds) {
        return Map.of();
    }
}
