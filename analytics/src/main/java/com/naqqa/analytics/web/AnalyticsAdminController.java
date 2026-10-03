package com.naqqa.analytics.web;

import com.naqqa.analytics.export.ExportService;
import com.naqqa.analytics.query.AnalyticsDtos;
import com.naqqa.analytics.query.AnalyticsQuery;
import com.naqqa.analytics.query.AnalyticsQueryService;
import com.naqqa.analytics.spi.BannerAnalyticsProvider;
import com.naqqa.analytics.spi.ChatAnalyticsProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("${naqqa.analytics.api-path:/api/analytics}")
public class AnalyticsAdminController {

    private final AnalyticsWebSupport web;
    private final RealtimeHub hub;
    private final ObjectProvider<BannerAnalyticsProvider> banners;
    private final ObjectProvider<ChatAnalyticsProvider> chat;

    public AnalyticsAdminController(AnalyticsWebSupport web, RealtimeHub hub, ObjectProvider<BannerAnalyticsProvider> banners,
                                    ObjectProvider<ChatAnalyticsProvider> chat) {
        this.web = web;
        this.hub = hub;
        this.banners = banners;
        this.chat = chat;
    }

    private AnalyticsQueryService q() {
        return web.queries();
    }

    @GetMapping("/overview")
    public AnalyticsDtos.Overview overview(Authentication auth, @RequestParam Map<String, String> params) {
        return q().overview(web.admin(auth, params, "overview"));
    }

    @GetMapping("/timeseries")
    public AnalyticsDtos.Timeseries timeseries(Authentication auth, @RequestParam Map<String, String> params) {
        return q().timeseries(web.admin(auth, params, "timeseries"));
    }

    @GetMapping("/top-entities")
    public AnalyticsDtos.TopEntities topEntities(Authentication auth, @RequestParam Map<String, String> params) {
        return q().topEntities(web.admin(auth, params, "top-entities"));
    }

    @GetMapping("/events")
    public AnalyticsDtos.EntityEvents events(Authentication auth, @RequestParam Map<String, String> params) {
        return q().entityEvents(web.admin(auth, params, "events"));
    }

    @GetMapping("/dimensions")
    public Object dimensions(Authentication auth, @RequestParam Map<String, String> params) {
        AnalyticsQuery query = web.access().admin(auth, web.parse(params, true));
        return web.dimensions(query, params.get("name"));
    }

    @GetMapping("/realtime")
    public AnalyticsDtos.Realtime realtime(Authentication auth) {
        web.access().require(auth, web.access().permissions().getRealtime(), web.access().permissions().getViewAll());
        return q().realtime(null);
    }

    @GetMapping(path = "/realtime/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter realtimeStream(Authentication auth) {
        web.access().require(auth, web.access().permissions().getRealtime(), web.access().permissions().getViewAll());
        return hub.subscribe(null);
    }

    @GetMapping("/acquisition")
    public AnalyticsDtos.Acquisition acquisition(Authentication auth, @RequestParam Map<String, String> params) {
        return q().acquisition(web.admin(auth, params, "acquisition"));
    }

    @GetMapping("/behavior")
    public AnalyticsDtos.Behavior behavior(Authentication auth, @RequestParam Map<String, String> params) {
        return q().behavior(web.admin(auth, params, "behavior"));
    }

    @GetMapping("/content")
    public AnalyticsDtos.Content content(Authentication auth, @RequestParam Map<String, String> params) {
        return q().content(web.admin(auth, params, "content"));
    }

    @GetMapping("/companies")
    public AnalyticsDtos.Companies companies(Authentication auth, @RequestParam Map<String, String> params) {
        return q().companies(web.admin(auth, params, "companies"));
    }

    @GetMapping("/search")
    public AnalyticsDtos.Search search(Authentication auth, @RequestParam Map<String, String> params) {
        return q().search(web.admin(auth, params, "search"));
    }

    @GetMapping("/funnels")
    public AnalyticsDtos.Funnel funnels(Authentication auth, @RequestParam Map<String, String> params) {
        AnalyticsQuery query = web.admin(auth, params, "funnels");
        return q().funnel(query, ExportService.steps(query), query.param("scope"));
    }

    @GetMapping("/cohorts")
    public AnalyticsDtos.Cohorts cohorts(Authentication auth, @RequestParam Map<String, String> params) {
        AnalyticsQuery query = web.admin(auth, params, "cohorts");
        return q().cohorts(query, ExportService.weeks(query));
    }

    @GetMapping("/audience")
    public AnalyticsDtos.Audience audience(Authentication auth, @RequestParam Map<String, String> params) {
        return q().audience(web.admin(auth, params, "audience"), false);
    }

    @GetMapping("/quality")
    public AnalyticsDtos.Quality quality(Authentication auth, @RequestParam Map<String, String> params) {
        return q().quality(web.admin(auth, params, "quality"));
    }

    @GetMapping("/banners")
    public Object banners(Authentication auth, @RequestParam Map<String, String> params) {
        AnalyticsQuery query = web.admin(auth, params, "banners", web.access().permissions().getViewAll(),
                web.access().permissions().getBannersManage());
        BannerAnalyticsProvider p = banners.getIfAvailable(() -> BannerAnalyticsProvider.NONE);
        Object out = p.available() ? p.admin(query) : null;
        return out == null ? web.unavailable(query) : out;
    }

    @GetMapping("/banners/{campaignId}")
    public Object banner(Authentication auth, @PathVariable String campaignId, @RequestParam Map<String, String> params) {
        AnalyticsQuery query = web.admin(auth, params, "banners", web.access().permissions().getViewAll(),
                web.access().permissions().getBannersManage());
        BannerAnalyticsProvider p = banners.getIfAvailable(() -> BannerAnalyticsProvider.NONE);
        Object out = p.available() ? p.campaign(campaignId, query) : null;
        return out == null ? web.unavailable(query) : out;
    }

    @GetMapping("/chat")
    public Object chat(Authentication auth, @RequestParam Map<String, String> params) {
        AnalyticsQuery query = web.admin(auth, params, "chat", web.access().permissions().getChatViewAll());
        ChatAnalyticsProvider p = chat.getIfAvailable(() -> ChatAnalyticsProvider.NONE);
        Object out = p.available() ? p.admin(query) : null;
        return out == null ? web.unavailable(query) : out;
    }

    @GetMapping("/chat/own")
    public Object chatOwn(Authentication auth, @RequestParam Map<String, String> params,
                          @RequestHeader(name = AnalyticsAccess.IMPERSONATE_HEADER, required = false) String impersonation) {
        web.access().require(auth, web.access().permissions().getChatViewOwn(), web.access().permissions().getImpersonatePartner());
        AnalyticsQuery query = web.partner(auth, params, impersonation, "chat-own");
        ChatAnalyticsProvider p = chat.getIfAvailable(() -> ChatAnalyticsProvider.NONE);
        Object out = p.available() ? p.own(query) : null;
        return out == null ? web.unavailable(query) : out;
    }
}
