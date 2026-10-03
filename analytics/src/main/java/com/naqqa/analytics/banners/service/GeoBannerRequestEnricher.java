package com.naqqa.analytics.banners.service;

import com.naqqa.analytics.banners.engine.BannerRequest;
import com.naqqa.analytics.banners.spi.BannerRequestEnricher;
import com.naqqa.analytics.spi.AnalyticsGeoResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;

import java.net.InetAddress;
import java.util.regex.Pattern;

public class GeoBannerRequestEnricher implements BannerRequestEnricher {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:]+");

    private final ObjectProvider<AnalyticsGeoResolver> geo;

    public GeoBannerRequestEnricher(ObjectProvider<AnalyticsGeoResolver> geo) {
        this.geo = geo;
    }

    @Override
    public BannerRequest enrich(BannerRequest request, HttpServletRequest http) {
        if (request.city() != null || http == null) {
            return request;
        }
        AnalyticsGeoResolver resolver = geo.getIfAvailable();
        String ip = http.getRemoteAddr();
        if (resolver == null || ip == null || !(IPV4.matcher(ip).matches() || (ip.contains(":") && IPV6.matcher(ip).matches()))) {
            return request;
        }
        try {
            AnalyticsGeoResolver.Geo g = resolver.lookup(InetAddress.getByName(ip));
            if (g == null || g.city() == null) {
                return request;
            }
            return new BannerRequest(request.slot(), request.lang(), request.pageType(), request.categoryId(), request.companyId(),
                    request.query(), request.device(), g.city(), request.newVisitor(), request.loggedIn(), request.vid(), request.sid(),
                    request.now());
        } catch (Exception e) {
            return request;
        }
    }
}
