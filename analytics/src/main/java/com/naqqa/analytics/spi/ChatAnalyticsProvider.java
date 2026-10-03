package com.naqqa.analytics.spi;

import com.naqqa.analytics.query.AnalyticsQuery;

public interface ChatAnalyticsProvider {

    ChatAnalyticsProvider NONE = new ChatAnalyticsProvider() {
    };

    default boolean available() {
        return false;
    }

    default Object admin(AnalyticsQuery query) {
        return null;
    }

    default Object own(AnalyticsQuery query) {
        return null;
    }
}
