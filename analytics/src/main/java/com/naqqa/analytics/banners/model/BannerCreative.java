package com.naqqa.analytics.banners.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Document("an_banner_creative")
public class BannerCreative {

    @Id
    private String id;
    @Indexed
    private String campaignId;
    private String name;
    private BannerImage desktop;
    private BannerImage mobile;
    private Map<String, String> alt = new LinkedHashMap<>();
    private Map<String, String> title = new LinkedHashMap<>();
    private Map<String, String> cta = new LinkedHashMap<>();
    private BannerDestination destination = new BannerDestination();
    private List<String> slots = new ArrayList<>();
    private int weight = 1;
    private boolean active = true;
    private Instant createdAt;
    private Instant updatedAt;
}
