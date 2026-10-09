package com.naqqa.analytics.banners.service;

import com.naqqa.analytics.banners.engine.BannerSlots;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.security.BannerTokenService;
import com.naqqa.analytics.banners.security.BannerTokenService.Claims;
import com.naqqa.analytics.banners.security.BannerUrlPolicy;
import com.naqqa.analytics.banners.spi.BannerEventRecorder;
import com.naqqa.analytics.banners.spi.BannerEventRecorder.BannerEvent;
import com.naqqa.analytics.banners.store.BannerCampaignCache;
import com.naqqa.analytics.banners.store.BannerCounters;
import com.naqqa.analytics.banners.store.BannerRepository;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;

@Slf4j
public class BannerClickService {

    public record ClickContext(String userAgent, String ip, String referer) {
    }

    public record ClickResult(String location, boolean recorded, boolean duplicate, boolean valid) {
    }

    private final BannerTokenService tokens;
    private final BannerRepository repository;
    private final BannerCounters counters;
    private final BannerCampaignCache cache;
    private final Supplier<BannerEventRecorder> recorder;
    private final Clock clock;
    private final Duration dedupWindow;
    private final boolean prefixLang;
    private final String fallback;

    public BannerClickService(BannerTokenService tokens, BannerRepository repository, BannerCounters counters,
                              BannerCampaignCache cache, Supplier<BannerEventRecorder> recorder, Clock clock,
                              Duration dedupWindow, boolean prefixLang, String fallback) {
        this.tokens = tokens;
        this.repository = repository;
        this.counters = counters;
        this.cache = cache;
        this.recorder = recorder;
        this.clock = clock;
        this.dedupWindow = dedupWindow;
        this.prefixLang = prefixLang;
        this.fallback = BannerUrlPolicy.validInternal(fallback) ? fallback : "/";
    }

    public ClickResult click(String token, ClickContext context) {
        Instant now = clock.instant();
        Claims claims = tokens.verify(token, now);
        if (claims == null) {
            return new ClickResult(fallback, false, false, false);
        }
        BannerCreative creative = repository.creative(claims.creativeId());
        if (creative == null || !claims.campaignId().equals(creative.getCampaignId())) {
            return new ClickResult(fallback, false, false, false);
        }
        String location = BannerUrlPolicy.resolve(creative.getDestination(), claims.lang(), prefixLang);
        if (location == null) {
            return new ClickResult(fallback, false, false, false);
        }
        String who = claims.vid() != null ? claims.vid() : hash(context.ip() + "|" + context.userAgent());
        String dedupKey = dedupKey(who, claims);
        if (!counters.firstClick(dedupKey, dedupWindow)) {
            return new ClickResult(location, false, true, true);
        }
        BannerCampaign campaign = repository.campaign(claims.campaignId());
        try {
            BannerSlots.Slot slot = BannerSlots.get(claims.slot());
            repository.incClicks(claims.campaignId(), claims.creativeId(), slot == null ? null : slot.id());
            cache.countClick(claims.campaignId());
        } catch (Exception e) {
            log.warn("Banner click counter could not be updated: {}", e.getMessage());
        }
        boolean noImpression = claims.vid() != null && !counters.seen(claims.vid(), claims.campaignId());
        try {
            recorder.get().record(new BannerEvent("banner_click", now, claims.vid(), claims.sid(), claims.lang(), claims.pageType(),
                    claims.campaignId(), claims.creativeId(), claims.bannerId(), claims.slot(),
                    campaign == null ? null : campaign.getCompanyId(), noImpression, context.userAgent(), context.ip(), context.referer()));
        } catch (Exception e) {
            log.warn("Banner click event could not be recorded: {}", e.getMessage());
        }
        return new ClickResult(location, true, false, true);
    }

    static String dedupKey(String who, Claims claims) {
        return hash(who) + ":" + claims.campaignId() + ":" + claims.creativeId();
    }

    static String hash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (Exception e) {
            return Integer.toHexString(String.valueOf(value).hashCode());
        }
    }
}
