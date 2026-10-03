package com.naqqa.analytics.banners.store;

import java.time.Duration;
import java.time.LocalDate;

public interface BannerCounters {

    int servedToday(String vid, String campaignId, LocalDate day);

    void recordServe(String vid, String campaignId, LocalDate day);

    boolean seen(String vid, String campaignId);

    boolean firstClick(String key, Duration window);
}
