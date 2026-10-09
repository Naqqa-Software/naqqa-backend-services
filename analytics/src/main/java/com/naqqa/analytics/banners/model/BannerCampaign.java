package com.naqqa.analytics.banners.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Document("an_banner_campaign")
public class BannerCampaign {

    @Id
    private String id;
    private String companyId;
    private String name;
    private BannerStatus status = BannerStatus.DRAFT;
    private BannerPriority priority = BannerPriority.INTERNAL;
    private Instant start;
    private Instant end;
    private Long budgetImpressions;
    private Long budgetClicks;
    private Integer frequencyCapPerDay;
    private int weight = 1;
    private boolean abTest;
    private boolean excludeCompetitorsOnCompanyPage;
    private boolean paid;
    private BannerPacing pacing = BannerPacing.EVEN;
    private BannerTargeting targeting = new BannerTargeting();
    private String createdBy;
    private String proposedByCompanyId;
    private String rejectionReason;
    private String reviewedBy;
    private Instant reviewedAt;
    private long servedImpressions;
    private long clicks;
    private Map<String, Long> slotServed = new LinkedHashMap<>();
    private Map<String, Long> slotClicks = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;
}
