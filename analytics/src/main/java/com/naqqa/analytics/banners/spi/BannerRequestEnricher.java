package com.naqqa.analytics.banners.spi;

import com.naqqa.analytics.banners.engine.BannerRequest;
import jakarta.servlet.http.HttpServletRequest;

public interface BannerRequestEnricher {

    BannerRequestEnricher NONE = (request, http) -> request;

    BannerRequest enrich(BannerRequest request, HttpServletRequest http);
}
