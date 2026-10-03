package com.naqqa.analytics.banners.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.naqqa.analytics.banners.engine.BannerPacingCalculator;
import com.naqqa.analytics.banners.engine.BannerSlots;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.model.BannerImage;

import java.util.List;

public final class BannerDtos {

    private BannerDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImageDto(String url, int w, int h) {
        public static ImageDto of(BannerImage image) {
            return image == null || image.getUrl() == null ? null : new ImageDto(image.getUrl(), image.getW(), image.getH());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImagesDto(ImageDto desktop, ImageDto mobile) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ServeDto(String bannerId, String campaignId, String creativeId, String slot, ImagesDto image, String alt,
                           String title, String cta, String href, boolean paid, boolean external) {
    }

    public record CampaignDetailDto(BannerCampaign campaign, List<BannerCreative> creatives, BannerPacingCalculator.Pacing pacing) {
    }

    public record PageDto<T>(List<T> content, long total, int page, int size) {
    }

    public record DecisionRequest(String reason) {
    }

    public record AssetDto(String url, int w, int h, long bytes, String format) {
    }

    public record SlotDto(String id, String page, BannerSlots.Size desktop, BannerSlots.Size mobile, boolean reservedForCompany,
                          boolean mounted) {
        public static SlotDto of(BannerSlots.Slot s) {
            return new SlotDto(s.id(), s.page(), s.desktop(), s.mobile(), s.reservedForCompany(), s.mounted());
        }
    }

    public record SummaryDto(long pending, long active) {
    }
}
