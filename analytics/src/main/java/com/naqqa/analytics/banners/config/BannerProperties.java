package com.naqqa.analytics.banners.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties("naqqa.analytics.banners")
public class BannerProperties {

    private boolean enabled = true;
    private String publicPath = "/api/public/banners";
    private String redirectPath = "/t/b";
    private String adminPath = "/api/admin/ad-campaigns";
    private String partnerPath = "/api/partner/ad-campaigns";
    private String secret = "";
    private int tokenMaxAgeDays = 30;
    private String timezone = "";
    private boolean prefixLang = true;
    private String fallbackRedirect = "/";
    private int clickDedupSeconds = 30;
    private int cacheSeconds = 30;
    private boolean seedSlots = true;
    private long assetImportMaxBytes = 5L * 1024 * 1024;
    private boolean excludeCompetitorsOnCompanyPage = true;
    private double pacingTolerance = 0.02;
    private long pacingMinSlack = 20;
    private long maxCreativeBytes = 300L * 1024;
    private double ratioTolerance = 0.05;
    private double maxScale = 3.0;
    private List<String> formats = new ArrayList<>(List.of("jpeg", "png", "webp", "avif", "gif"));
    private int memoryCounterEntries = 200_000;
    private String redisPrefix = "naqqa:an:bn:";
    private int statsMaxEvents = 2_000_000;
    private int postClickWindowMinutes = 30;
    private List<String> conversionEvents = new ArrayList<>(List.of("promocode_checkout_start", "promocode_purchase",
            "share_click", "add_to_list", "store_contact_click", "store_link_click", "signup_complete"));
}
