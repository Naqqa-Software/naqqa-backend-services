package com.naqqa.analytics.banners.service;

import com.naqqa.analytics.banners.engine.BannerRequest;
import com.naqqa.analytics.banners.engine.BannerSelector;
import com.naqqa.analytics.banners.engine.BannerSelector.Candidate;
import com.naqqa.analytics.banners.engine.BannerSlotDefaults;
import com.naqqa.analytics.banners.engine.BannerSlots;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.model.BannerSlotSettings;
import com.naqqa.analytics.banners.model.BannerStatus;
import com.naqqa.analytics.banners.store.BannerCampaignCache;
import com.naqqa.analytics.banners.store.BannerRepository;
import com.naqqa.analytics.banners.web.BannerDtos.ImageDto;
import com.naqqa.analytics.banners.web.BannerDtos.ZoneDto;
import com.naqqa.analytics.banners.web.BannerDtos.ZoneRowDto;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class BannerZoneService {

    public static final List<String> LANGS = List.of("ro", "ru");
    public static final List<String> DEVICES = List.of("desktop", "mobile");

    private final BannerRepository repository;
    private final BannerCampaignCache cache;
    private final BannerSelector selector;
    private final BannerSlotRegistry slots;
    private final Clock clock;

    public BannerZoneService(BannerRepository repository, BannerCampaignCache cache, BannerSelector selector, BannerSlotRegistry slots,
                             Clock clock) {
        this.repository = repository;
        this.cache = cache;
        this.selector = selector;
        this.slots = slots;
        this.clock = clock;
    }

    public List<ZoneDto> zones() {
        Instant now = clock.instant();
        List<BannerCampaign> campaigns = repository.all().stream().filter(c -> c.getStatus() != BannerStatus.REJECTED).toList();
        Map<String, List<BannerCreative>> creatives = repository.creatives(campaigns.stream().map(BannerCampaign::getId).toList()).stream()
                .collect(Collectors.groupingBy(BannerCreative::getCampaignId));
        List<Candidate> live = cache.candidates();
        Set<String> liveIds = new HashSet<>();
        for (Candidate c : live) {
            liveIds.add(c.campaign().getId());
        }
        List<ZoneDto> out = new ArrayList<>(BannerSlots.ALL.size());
        for (BannerSlots.Slot slot : BannerSlots.ALL) {
            BannerSlotSettings settings = slots == null ? BannerSlotRegistry.defaults(slot) : slots.settings(slot.id());
            List<ZoneRowDto> rows = new ArrayList<>();
            for (BannerCampaign c : campaigns) {
                if (!targets(c, slot.id())) {
                    continue;
                }
                List<BannerCreative> own = creatives.getOrDefault(c.getId(), List.of()).stream().filter(cr -> fits(cr, slot.id())).toList();
                for (BannerCreative cr : own) {
                    boolean started = c.getStart() == null || !now.isBefore(c.getStart());
                    rows.add(row(c, cr, slot.id(), own.size(), liveIds.contains(c.getId()) && started && cr.isActive()));
                }
            }
            out.add(new ZoneDto(slot.id(), slot.page(), settings.getName(), settings.isEnabled(), settings.isMounted(),
                    settings.isReservedForCompany(), settings.getDesktop(), settings.getMobile(), coverage(slot, settings, live, now), rows));
        }
        return out;
    }

    public Map<String, Boolean> coverage(BannerSlots.Slot slot, BannerSlotSettings settings, List<Candidate> live, Instant now) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        List<String> pageTypes = new ArrayList<>();
        if (settings != null && settings.getPageTypes() != null && !settings.getPageTypes().isEmpty()) {
            pageTypes.addAll(settings.getPageTypes());
        } else {
            pageTypes.addAll(BannerSlotDefaults.get(slot.id()).pageTypeOptions());
            pageTypes.add(null);
        }
        for (String lang : LANGS) {
            for (String device : DEVICES) {
                boolean covered = false;
                for (String pageType : pageTypes) {
                    BannerRequest request = BannerRequest.builder().slot(slot.id()).lang(lang).device(device).pageType(pageType).now(now).build();
                    if (slots != null && !slots.servable(request)) {
                        continue;
                    }
                    if (!selector.eligible(live, request, null).isEmpty()) {
                        covered = true;
                        break;
                    }
                }
                out.put(lang + "-" + device, covered);
            }
        }
        return out;
    }

    static boolean targets(BannerCampaign c, String slot) {
        return c.getTargeting() != null && c.getTargeting().getSlots() != null
                && c.getTargeting().getSlots().stream().anyMatch(slot::equalsIgnoreCase);
    }

    static boolean fits(BannerCreative cr, String slot) {
        return cr.getSlots() == null || cr.getSlots().isEmpty() || cr.getSlots().stream().anyMatch(slot::equalsIgnoreCase);
    }

    static ZoneRowDto row(BannerCampaign c, BannerCreative cr, String slot, int siblings, boolean live) {
        long impressions;
        long clicks;
        boolean tracked = cr.getServed() > 0 || (cr.getSlotServed() != null && !cr.getSlotServed().isEmpty());
        if (tracked) {
            impressions = value(cr.getSlotServed(), slot);
            clicks = value(cr.getSlotClicks(), slot);
        } else if (siblings == 1 && c.getTargeting().getSlots().size() == 1) {
            impressions = c.getServedImpressions();
            clicks = c.getClicks();
        } else {
            impressions = 0;
            clicks = 0;
        }
        return new ZoneRowDto(c.getId(), c.getName(), c.getCompanyId(), String.valueOf(c.getStatus()), String.valueOf(c.getPriority()),
                c.isPaid(), c.getWeight(), c.getStart(), c.getEnd(), c.getTargeting().getLangs(), c.getTargeting().getDevices(), cr.getId(),
                cr.getName(), cr.isActive(), cr.getTitle(), cr.getAlt(), ImageDto.of(cr.getDesktop()), ImageDto.of(cr.getMobile()),
                impressions, clicks, live);
    }

    private static long value(Map<String, Long> values, String key) {
        if (values == null) {
            return 0;
        }
        Long v = values.get(key);
        return v == null ? 0 : v;
    }
}
