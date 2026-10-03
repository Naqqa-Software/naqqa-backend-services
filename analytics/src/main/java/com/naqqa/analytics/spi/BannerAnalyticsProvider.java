package com.naqqa.analytics.spi;

import com.naqqa.analytics.query.AnalyticsQuery;

public interface BannerAnalyticsProvider {

    BannerAnalyticsProvider NONE = new BannerAnalyticsProvider() {
    };

    default boolean available() {
        return false;
    }

    default Object admin(AnalyticsQuery query) {
        return null;
    }

    default Object campaign(String campaignId, AnalyticsQuery query) {
        return null;
    }

    default Object partner(AnalyticsQuery query) {
        return null;
    }
}
