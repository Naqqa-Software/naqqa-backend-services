package com.naqqa.analytics.banners.web;

import com.naqqa.analytics.banners.service.BannerStatsService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

final class BannerCsvResponse {

    private BannerCsvResponse() {
    }

    static ResponseEntity<byte[]> of(BannerStatsService.CampaignReport report) {
        String name = "campaign-" + report.campaign().getId() + "-" + report.from() + "_" + report.to() + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(BannerStatsService.csv(report).getBytes(StandardCharsets.UTF_8));
    }
}
