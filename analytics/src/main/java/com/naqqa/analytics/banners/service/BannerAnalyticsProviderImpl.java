package com.naqqa.analytics.banners.service;

import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.store.BannerRepository;
import com.naqqa.analytics.query.AnalyticsQuery;
import com.naqqa.analytics.spi.BannerAnalyticsProvider;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public class BannerAnalyticsProviderImpl implements BannerAnalyticsProvider {

    private final BannerStatsService stats;
    private final BannerRepository repository;

    public BannerAnalyticsProviderImpl(BannerStatsService stats, BannerRepository repository) {
        this.stats = stats;
        this.repository = repository;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Object admin(AnalyticsQuery query) {
        return BannerStatsService.view(stats.overview(query.from(), query.to(), companies(query)));
    }

    @Override
    public Object campaign(String campaignId, AnalyticsQuery query) {
        BannerCampaign campaign = repository.campaign(campaignId);
        if (campaign == null) {
            return null;
        }
        Collection<String> allowed = companies(query);
        if (allowed != null && (campaign.getCompanyId() == null || !allowed.contains(campaign.getCompanyId()))) {
            return null;
        }
        return BannerStatsService.view(stats.campaign(campaign, query.from(), query.to()));
    }

    @Override
    public Object partner(AnalyticsQuery query) {
        if (!query.scoped() || query.companyIds().isEmpty()) {
            return BannerStatsService.view(stats.overview(query.from(), query.to(), Set.of()));
        }
        return BannerStatsService.view(stats.overview(query.from(), query.to(), companies(query)));
    }

    static Collection<String> companies(AnalyticsQuery query) {
        String filter = query.filter("companyId");
        if (query.scoped()) {
            if (filter != null && !filter.isBlank()) {
                return query.companyIds().contains(filter) ? List.of(filter) : Set.of();
            }
            return query.companyIds();
        }
        return filter == null || filter.isBlank() ? null : List.of(filter);
    }
}
