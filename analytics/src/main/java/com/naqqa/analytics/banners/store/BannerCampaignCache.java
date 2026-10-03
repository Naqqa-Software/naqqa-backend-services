package com.naqqa.analytics.banners.store;

import com.naqqa.analytics.banners.engine.BannerPacingCalculator;
import com.naqqa.analytics.banners.engine.BannerSelector.Candidate;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class BannerCampaignCache {

    private final BannerRepository repository;
    private final Clock clock;
    private final long ttlMs;
    private volatile List<Candidate> snapshot = List.of();
    private volatile long loadedAt;
    private final Object lock = new Object();

    public BannerCampaignCache(BannerRepository repository, Clock clock, long ttlMs) {
        this.repository = repository;
        this.clock = clock;
        this.ttlMs = Math.max(1000L, ttlMs);
    }

    public List<Candidate> candidates() {
        long now = clock.millis();
        if (now - loadedAt > ttlMs) {
            synchronized (lock) {
                if (now - loadedAt > ttlMs) {
                    reload();
                }
            }
        }
        return snapshot;
    }

    public void invalidate() {
        loadedAt = 0;
    }

    public void countServe(String campaignId) {
        for (Candidate c : snapshot) {
            if (c.campaign().getId().equals(campaignId)) {
                synchronized (c.campaign()) {
                    c.campaign().setServedImpressions(c.campaign().getServedImpressions() + 1);
                }
                return;
            }
        }
    }

    public void countClick(String campaignId) {
        for (Candidate c : snapshot) {
            if (c.campaign().getId().equals(campaignId)) {
                synchronized (c.campaign()) {
                    c.campaign().setClicks(c.campaign().getClicks() + 1);
                }
                return;
            }
        }
    }

    private void reload() {
        try {
            Instant now = clock.instant();
            List<BannerCampaign> active = repository.active();
            List<BannerCampaign> live = new ArrayList<>();
            for (BannerCampaign c : active) {
                boolean expired = c.getEnd() != null && !now.isBefore(c.getEnd());
                if (expired || BannerPacingCalculator.exhausted(c)) {
                    repository.endIfActive(c.getId());
                } else {
                    live.add(c);
                }
            }
            Map<String, List<BannerCreative>> byCampaign = new HashMap<>(repository.creatives(live.stream().map(BannerCampaign::getId).toList())
                    .stream().collect(Collectors.groupingBy(BannerCreative::getCampaignId)));
            List<Candidate> out = new ArrayList<>(live.size());
            for (BannerCampaign c : live) {
                out.add(new Candidate(c, List.copyOf(byCampaign.getOrDefault(c.getId(), List.of()))));
            }
            snapshot = List.copyOf(out);
        } catch (Exception e) {
            log.warn("Banner campaigns could not be loaded: {}", e.getMessage());
        }
        loadedAt = clock.millis();
    }
}
