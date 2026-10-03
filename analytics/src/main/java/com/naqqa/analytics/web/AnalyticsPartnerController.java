package com.naqqa.analytics.web;

import com.naqqa.analytics.query.AnalyticsDtos;
import com.naqqa.analytics.query.AnalyticsQuery;
import com.naqqa.analytics.query.AnalyticsQueryService;
import com.naqqa.analytics.spi.BannerAnalyticsProvider;
import com.naqqa.analytics.spi.ChatAnalyticsProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("${naqqa.analytics.api-path:/api/analytics}/partner")
public class AnalyticsPartnerController {

    private static final String H = AnalyticsAccess.IMPERSONATE_HEADER;

    private final AnalyticsWebSupport web;
    private final ObjectProvider<BannerAnalyticsProvider> banners;
    private final ObjectProvider<ChatAnalyticsProvider> chat;

    public AnalyticsPartnerController(AnalyticsWebSupport web, ObjectProvider<BannerAnalyticsProvider> banners,
                                      ObjectProvider<ChatAnalyticsProvider> chat) {
        this.web = web;
        this.banners = banners;
        this.chat = chat;
    }

    private AnalyticsQueryService q() {
        return web.queries();
    }

    @GetMapping("/companies")
    public List<AnalyticsDtos.CompanyRef> companies(Authentication auth, @RequestHeader(name = H, required = false) String imp) {
        return q().companyRefs(web.access().partnerCompanies(auth, imp));
    }

    @GetMapping("/overview")
    public AnalyticsDtos.PartnerOverview overview(Authentication auth, @RequestParam Map<String, String> params,
                                                  @RequestHeader(name = H, required = false) String imp) {
        return q().partnerOverview(web.partner(auth, params, imp, "partner-overview"));
    }

    @GetMapping("/items")
    public AnalyticsDtos.PartnerItems items(Authentication auth, @RequestParam Map<String, String> params,
                                            @RequestHeader(name = H, required = false) String imp) {
        return q().partnerItems(web.partner(auth, params, imp, "partner-items"));
    }

    @GetMapping("/booklets")
    public AnalyticsDtos.PartnerBooklets booklets(Authentication auth, @RequestParam Map<String, String> params,
                                                  @RequestHeader(name = H, required = false) String imp) {
        return q().partnerBooklets(web.partner(auth, params, imp, "partner-booklets"));
    }

    @GetMapping("/searches")
    public AnalyticsDtos.PartnerSearches searches(Authentication auth, @RequestParam Map<String, String> params,
                                                  @RequestHeader(name = H, required = false) String imp) {
        return q().partnerSearches(web.partner(auth, params, imp, "partner-searches"));
    }

    @GetMapping("/benchmark")
    public AnalyticsDtos.Benchmark benchmark(Authentication auth, @RequestParam Map<String, String> params,
                                             @RequestHeader(name = H, required = false) String imp) {
        return q().partnerBenchmark(web.partner(auth, params, imp, "partner-benchmark"));
    }

    @GetMapping("/audience")
    public AnalyticsDtos.Audience audience(Authentication auth, @RequestParam Map<String, String> params,
                                           @RequestHeader(name = H, required = false) String imp) {
        return q().audience(web.partner(auth, params, imp, "partner-audience"), true);
    }

    @GetMapping("/timeseries")
    public AnalyticsDtos.Timeseries timeseries(Authentication auth, @RequestParam Map<String, String> params,
                                               @RequestHeader(name = H, required = false) String imp) {
        return q().timeseries(web.partner(auth, params, imp, "partner-timeseries"));
    }

    @GetMapping("/top-entities")
    public AnalyticsDtos.TopEntities topEntities(Authentication auth, @RequestParam Map<String, String> params,
                                                 @RequestHeader(name = H, required = false) String imp) {
        return q().topEntities(web.partner(auth, params, imp, "partner-top-entities"));
    }

    @GetMapping("/events")
    public AnalyticsDtos.EntityEvents events(Authentication auth, @RequestParam Map<String, String> params,
                                             @RequestHeader(name = H, required = false) String imp) {
        return q().entityEvents(web.partner(auth, params, imp, "partner-events"));
    }

    @GetMapping("/dimensions")
    public Object dimensions(Authentication auth, @RequestParam Map<String, String> params,
                             @RequestHeader(name = H, required = false) String imp) {
        AnalyticsQuery query = web.access().partner(auth, web.parse(params, false), AnalyticsAccess.requestedCompany(params), imp);
        return web.dimensions(query, params.get("name"));
    }

    @GetMapping("/banners")
    public Object banners(Authentication auth, @RequestParam Map<String, String> params, @RequestHeader(name = H, required = false) String imp) {
        AnalyticsQuery query = web.partner(auth, params, imp, "partner-banners");
        BannerAnalyticsProvider p = banners.getIfAvailable(() -> BannerAnalyticsProvider.NONE);
        Object out = p.available() ? p.partner(query) : null;
        return out == null ? web.unavailable(query) : out;
    }

    @GetMapping("/chat")
    public Object chat(Authentication auth, @RequestParam Map<String, String> params, @RequestHeader(name = H, required = false) String imp) {
        AnalyticsQuery query = web.partner(auth, params, imp, "partner-chat");
        ChatAnalyticsProvider p = chat.getIfAvailable(() -> ChatAnalyticsProvider.NONE);
        Object out = p.available() ? p.own(query) : null;
        return out == null ? web.unavailable(query) : out;
    }
}
