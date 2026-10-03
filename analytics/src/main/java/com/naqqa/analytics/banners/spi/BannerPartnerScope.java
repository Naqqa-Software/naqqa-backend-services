package com.naqqa.analytics.banners.spi;

import org.springframework.security.core.Authentication;

import java.util.Set;

public interface BannerPartnerScope {

    BannerPartnerScope NONE = authentication -> Set.of();

    Set<String> companyIds(Authentication authentication);
}
