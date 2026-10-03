package com.naqqa.analytics.banners.spi;

import java.time.Instant;

public interface BannerEventRecorder {

    BannerEventRecorder NONE = event -> {
    };

    record BannerEvent(String name, Instant ts, String vid, String sid, String lang, String pageType, String campaignId,
                       String creativeId, String bannerId, String slot, String companyId, boolean noPriorImpression,
                       String userAgent, String ip, String referer) {
    }

    void record(BannerEvent event);
}
