package com.naqqa.analytics.banners.web;

import com.naqqa.analytics.banners.engine.BannerRequest;
import com.naqqa.analytics.banners.engine.BannerTargetingEngine;
import com.naqqa.analytics.banners.security.BannerAccess;
import com.naqqa.analytics.banners.service.BannerClickService;
import com.naqqa.analytics.banners.service.BannerDeliveryService;
import com.naqqa.analytics.banners.spi.BannerRequestEnricher;
import com.naqqa.analytics.banners.web.BannerDtos.ServeDto;
import com.naqqa.analytics.collect.UserAgentParser;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@RestController
public class BannerPublicController {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    private final BannerDeliveryService delivery;
    private final BannerClickService clicks;
    private final Supplier<BannerRequestEnricher> enricher;
    private final Clock clock;

    public BannerPublicController(BannerDeliveryService delivery, BannerClickService clicks, Supplier<BannerRequestEnricher> enricher, Clock clock) {
        this.delivery = delivery;
        this.clicks = clicks;
        this.enricher = enricher;
        this.clock = clock;
    }

    @GetMapping("${naqqa.analytics.banners.public-path:/api/public/banners}/serve")
    public ResponseEntity<ServeDto> serve(@RequestParam String slot,
                                          @RequestParam(required = false) String lang,
                                          @RequestParam(required = false) String pageType,
                                          @RequestParam(required = false) String categoryId,
                                          @RequestParam(required = false) String companyId,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) String device,
                                          @RequestParam(required = false) String vid,
                                          @RequestParam(required = false) String sid,
                                          @RequestParam(required = false) String nv,
                                          HttpServletRequest http, Authentication authentication) {
        String visitor = id(vid != null ? vid : http.getHeader("X-Analytics-Vid"));
        if (visitor == null && http.getCookies() != null) {
            for (Cookie cookie : http.getCookies()) {
                if ("naqqa_vid".equals(cookie.getName())) {
                    visitor = id(cookie.getValue());
                }
            }
        }
        String session = id(sid != null ? sid : http.getHeader("X-Analytics-Sid"));
        String dev = BannerTargetingEngine.normalizeDevice(device);
        if (dev == null) {
            UserAgentParser.UserAgentInfo ua = UserAgentParser.parse(http.getHeader(HttpHeaders.USER_AGENT));
            dev = ua == null ? null : ua.device();
        }
        BannerRequest request = BannerRequest.builder()
                .slot(slot == null ? null : slot.trim().toLowerCase())
                .lang(lang(lang))
                .pageType(clip(pageType, 40))
                .categoryId(id(categoryId))
                .companyId(id(companyId))
                .query(clip(q, 200))
                .device(dev)
                .newVisitor(nv == null ? null : ("1".equals(nv) || "true".equalsIgnoreCase(nv)))
                .loggedIn(BannerAccess.of(authentication).loggedIn())
                .vid(visitor)
                .sid(session)
                .now(clock.instant())
                .build();
        BannerRequestEnricher e = enricher.get();
        if (e != null) {
            request = e.enrich(request, http);
        }
        ServeDto dto = delivery.serve(request);
        if (dto == null) {
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dto);
    }

    @GetMapping("${naqqa.analytics.banners.redirect-path:/t/b}/{token}")
    public ResponseEntity<Void> redirect(@PathVariable String token, HttpServletRequest http) {
        BannerClickService.ClickResult result = clicks.click(token, new BannerClickService.ClickContext(
                http.getHeader(HttpHeaders.USER_AGENT), http.getRemoteAddr(), http.getHeader(HttpHeaders.REFERER)));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(result.location()))
                .cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex, nofollow")
                .header("Referrer-Policy", "origin")
                .build();
    }

    private static String id(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return ID.matcher(v).matches() ? v : null;
    }

    private static String lang(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase();
        return v.matches("[a-z]{2}") ? v : null;
    }

    private static String clip(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
