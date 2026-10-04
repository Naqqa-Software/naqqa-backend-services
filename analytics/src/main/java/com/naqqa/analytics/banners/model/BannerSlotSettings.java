package com.naqqa.analytics.banners.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Document("an_banner_slot")
public class BannerSlotSettings {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Size {
        private int w;
        private int h;
    }

    @Id
    private String id;
    private boolean enabled = true;
    private Map<String, String> name = new LinkedHashMap<>();
    private Map<String, String> description = new LinkedHashMap<>();
    private String page;
    private Size desktop;
    private Size mobile;
    private boolean desktopEnabled = true;
    private boolean mobileEnabled = true;
    private List<String> pageTypes = new ArrayList<>();
    private Boolean showLabel;
    private Boolean lazy;
    private Boolean reserve;
    private Boolean eager;
    private Map<String, Object> params = new LinkedHashMap<>();
    private boolean reservedForCompany;
    private boolean mounted = true;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
}
