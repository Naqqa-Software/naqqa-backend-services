package com.naqqa.analytics.banners.service;

import com.naqqa.analytics.banners.engine.BannerRequest;
import com.naqqa.analytics.banners.engine.BannerSelector;
import com.naqqa.analytics.banners.engine.BannerSelector.Selection;
import com.naqqa.analytics.banners.engine.BannerSlots;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.model.BannerPriority;
import com.naqqa.analytics.banners.security.BannerTokenService;
import com.naqqa.analytics.banners.security.BannerUrlPolicy;
import com.naqqa.analytics.banners.store.BannerCampaignCache;
import com.naqqa.analytics.banners.store.BannerCounters;
import com.naqqa.analytics.banners.store.BannerRepository;
import com.naqqa.analytics.banners.web.BannerDtos.ImageDto;
import com.naqqa.analytics.banners.web.BannerDtos.ImagesDto;
import com.naqqa.analytics.banners.web.BannerDtos.ServeDto;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

@Slf4j
public class BannerDeliveryService {

    private final BannerCampaignCache cache;
    private final BannerSelector selector;
    private final BannerCounters counters;
    private final BannerRepository repository;
    private final BannerTokenService tokens;
    private final ZoneId zone;
    private final String redirectPath;
    private final RandomGenerator random;

    public BannerDeliveryService(BannerCampaignCache cache, BannerSelector selector, BannerCounters counters,
                                 BannerRepository repository, BannerTokenService tokens, ZoneId zone, String redirectPath,
                                 RandomGenerator random) {
        this.cache = cache;
        this.selector = selector;
        this.counters = counters;
        this.repository = repository;
        this.tokens = tokens;
        this.zone = zone;
        this.redirectPath = redirectPath == null ? "/t/b" : redirectPath.replaceAll("/+$", "");
        this.random = random;
    }

    public ServeDto serve(BannerRequest request) {
        if (request.slot() == null || !BannerSlots.exists(request.slot())) {
            return null;
        }
        LocalDate day = request.now().atZone(zone).toLocalDate();
        List<BannerSelector.Candidate> candidates = cache.candidates();
        Selection selection = selector.select(candidates, request,
                campaignId -> counters.servedToday(request.vid(), campaignId, day), random);
        if (selection == null) {
            return null;
        }
        BannerCampaign campaign = selection.campaign();
        BannerCreative creative = selection.creative();
        if (!BannerUrlPolicy.valid(creative.getDestination())) {
            log.warn("Banner creative {} has an invalid destination; skipped", creative.getId());
            return null;
        }
        counters.recordServe(request.vid(), campaign.getId(), day);
        cache.countServe(campaign.getId());
        try {
            repository.incServed(campaign.getId());
        } catch (Exception e) {
            log.warn("Banner served counter could not be updated: {}", e.getMessage());
        }
        String bannerId = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String token = tokens.sign(new BannerTokenService.Claims(campaign.getId(), creative.getId(), request.slot(), request.lang(),
                request.vid(), request.sid(), bannerId, request.pageType(), request.now()));
        boolean paid = campaign.isPaid() || campaign.getPriority() == BannerPriority.PAID;
        return new ServeDto(bannerId, campaign.getId(), creative.getId(), BannerSlots.get(request.slot()).id(),
                new ImagesDto(ImageDto.of(creative.getDesktop()), ImageDto.of(creative.getMobile())),
                text(creative.getAlt(), request.lang()), text(creative.getTitle(), request.lang()), text(creative.getCta(), request.lang()),
                redirectPath + "/" + token, paid, creative.getDestination().external());
    }

    public static String text(Map<String, String> values, String lang) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (lang != null) {
            String v = values.get(lang.toLowerCase());
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        String ro = values.get("ro");
        if (ro != null && !ro.isBlank()) {
            return ro;
        }
        return values.values().stream().filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
    }
}
